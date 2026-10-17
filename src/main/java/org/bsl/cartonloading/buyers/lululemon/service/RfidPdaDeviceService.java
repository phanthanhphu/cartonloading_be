package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaDevice;
import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaCapture;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidPdaCaptureRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import jakarta.annotation.PostConstruct;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidPdaDeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Wired RF88 pilot. Requires ADB on the Spring Boot HOST machine and Android USB debugging.
 * No RFID command is sent: controlled taps act on the already installed RFID Control V2 UI.
 * ADB success confirms a tap was injected, NOT that Inventory actually started/stopped.
 */
@Service
public class RfidPdaDeviceService {
    private static final String EXPORT_FOLDER = "/sdcard/Download/RFIDControlV2/CSV";
    private static final Pattern SERIAL_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern EXCEL_PATTERN = Pattern.compile("inventory_[0-9]{8}_[0-9]{6}\\.xlsx", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPC_PATTERN = Pattern.compile("(?i)(?:[0-9a-f]{28}|[0-9a-f]{24})");
    private final RfidPdaDeviceRepository configs;
    private final LululemonRfidService cartons;
    private final BuyerFactoryAccessService factoryAccess;
    private final String adb;
    // Persist reservations across backend restart and across backend JVM instances.
    private final RfidPdaCaptureRepository captureRepository;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;

    @PostConstruct
    public void createCaptureUniqueIndex() {
        mongoTemplate.indexOps(RfidPdaCapture.class).ensureIndex(
                new Index().on("sessionId", Sort.Direction.ASC).unique());
    }

    private Capture currentCapture(String serial) {
        return captureRepository.findById(serial).map(this::decodeCapture).orElse(null);
    }

    private Capture decodeCapture(RfidPdaCapture c) {
        return new Capture(c.getStation(), c.getOrder(), c.getPo(), c.getSscc(),
                c.getSessionId(), c.getBaselineFiles(), c.isStopped(), c.getStartedAt());
    }

    private RfidPdaCapture encodeCapture(String serial, Capture c) {
        RfidPdaCapture document = new RfidPdaCapture();
        document.setSerial(serial); document.setStation(c.station()); document.setOrder(c.order());
        document.setPo(c.po()); document.setSscc(c.sscc()); document.setSessionId(c.sessionId());
        document.setBaselineFiles(new LinkedHashSet<>(c.baselineFiles()));
        document.setStopped(c.stopped()); document.setStartedAt(c.startedAt());
        return document;
    }

    public RfidPdaDeviceService(RfidPdaDeviceRepository configs, LululemonRfidService cartons,
                                BuyerFactoryAccessService factoryAccess, RfidPdaCaptureRepository captureRepository,
                                MongoTemplate mongoTemplate, AuditLogService auditLogService,
                                @Value("${lululemon.rfid.adb-path:adb}") String adb) {
        this.configs = configs;
        this.cartons = cartons;
        this.factoryAccess = factoryAccess;
        this.captureRepository = captureRepository;
        this.mongoTemplate = mongoTemplate;
        this.auditLogService = auditLogService;
        this.adb = adb;
    }

    public record Device(String serial, String label, String stationCode, boolean configured,
                         boolean online, String state, Integer startX, Integer startY,
                         Integer stopX, Integer stopY, boolean capturing, String capturePhase,
                         String captureSessionId, String factoryCode, boolean defaultReader,
                         List<String> factoryCodes, List<String> defaultFactoryCodes, boolean registered) { }
    public record DeviceSettings(String label, String stationCode, Integer startX, Integer startY,
                                 Integer stopX, Integer stopY) { }
    public record GlobalDeviceSettings(String label, Integer startX, Integer startY,
                                       Integer stopX, Integer stopY, List<String> factoryCodes) { }
    public record CaptureResult(String serial, String sessionId, String status, String message) { }
    /** Diagnostics for the exact exports that contributed to a preview; timestamps are PDA epoch seconds. */
    public record ExportFileSummary(String filename, long modifiedAtEpochSecond,
                                    int candidateCells, int validCells, int duplicateCells, int rejectedEpcCells) { }
    public record Preview(String serial, String sessionId, String filename, int uniqueCount,
                          List<String> epcs, int ignoredCells, long captureStartedAtEpochSecond,
                          int sourceFileCount, int candidateCells, int duplicateCells,
                          int rejectedEpcCells, List<ExportFileSummary> sourceFiles,
                          int excelUniqueTagCount, int excelTotalReadCount, int excelRawUniqueEpcCount,
                          List<String> rejectedEpcs, List<String> skippedFiles, List<String> reads) { }
    private record Capture(String station, String order, String po, String sscc, String sessionId,
                           Set<String> baselineFiles, boolean stopped, Instant startedAt) { }

    /** Carton UI sees only readers assigned to the carton Factory; default is per Factory. */
    public List<Device> devices(String stationCode, String factoryCode) {
        station(stationCode);
        String factory = factory(factoryCode);
        Map<String, String> online = connectedDevices();
        List<Device> result = new ArrayList<>();
        for (RfidPdaDevice config : configs.findAll()) {
            Set<String> assigned = assignedFactories(config);
            if (assigned.contains(factory))
                result.add(asDevice(config, online.get(config.getSerial()), factory));
        }
        result.sort(Comparator.comparing(Device::defaultReader).reversed()
                .thenComparing(Device::label, Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    /** All saved readers plus all currently detected USB/ADB devices, in one global list. */
    public List<Device> managementDevices() {
        Map<String, String> online = connectedDevices();
        List<Device> result = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        for (RfidPdaDevice config : configs.findAll()) {
            result.add(asDevice(config, online.get(config.getSerial()), null));
            listed.add(config.getSerial());
        }
        for (Map.Entry<String, String> entry : online.entrySet()) {
            if (!listed.contains(entry.getKey())) {
                result.add(new Device(entry.getKey(), entry.getKey(), null, false,
                        "device".equals(entry.getValue()), entry.getValue(), null, null,
                        null, null, captureRepository.existsById(entry.getKey()), "IDLE", null,
                        null, false, List.of(), List.of(), false));
            }
        }
        result.sort(Comparator.comparing(Device::label, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(Device::serial));
        return result;
    }

    /** Global settings: one Reader may be assigned to multiple factories; capture stays exclusive by serial. */
    public synchronized Device configureDevice(String serial, GlobalDeviceSettings settings) {
        validSerial(serial);
        if (settings == null) throw new IllegalArgumentException("Missing reader settings.");
        if (captureRepository.existsById(serial)) throw new IllegalArgumentException("Reader is reserved for a carton.");
        // ADMIN may assign a reader before touch coordinates are calibrated.
        // An uncalibrated reader remains NOT READY and cannot start inventory.
        boolean anyCoordinate = settings.startX() != null || settings.startY() != null
                || settings.stopX() != null || settings.stopY() != null;
        if (anyCoordinate) {
            point(settings.startX(), settings.startY());
            point(settings.stopX(), settings.stopY());
        }
        RfidPdaDevice config = configs.findById(serial).orElse(null);
        if (config == null) {
            requireOnline(serial); // New device must be physically discovered by ADB.
            config = new RfidPdaDevice();
            config.setSerial(serial);
        }
        Set<String> previous = assignedFactories(config);
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        if (settings.factoryCodes() != null) {
            for (String value : settings.factoryCodes()) wanted.add(normalizeFactory(value));
        }
        // Users cannot add/remove assignments to factories where they have no weighing rights.
        for (String code : previous) if (!wanted.contains(code)) factoryAccess.assertWeighingFactoryAccess(code);
        for (String code : wanted) if (!previous.contains(code)) factoryAccess.assertWeighingFactoryAccess(code);
        String label = settings.label() == null ? "" : settings.label().trim();
        config.setLabel(label.isBlank() ? serial : label.substring(0, Math.min(label.length(), 80)));
        config.setStartX(settings.startX()); config.setStartY(settings.startY());
        config.setStopX(settings.stopX()); config.setStopY(settings.stopY());
        Set<String> defaults = defaultFactories(config);
        defaults.retainAll(wanted);
        updateAssignments(config, wanted, defaults);
        if (!previous.equals(wanted)) {
            // Audit BEFORE mutation: a failed audit must never silently change Factory ownership.
            auditLogService.log(RequestActor.current(), "RFID_READER_FACTORY_ASSIGNMENT",
                    AppConstants.AuditResource.CARTON_LOADING, serial,
                    "Previous=" + previous + "; Assigned=" + wanted, null, null);
        }
        configs.save(config);
        for (String code : wanted) ensureDefault(code);
        for (String code : previous) if (!wanted.contains(code)) ensureDefault(code);
        return asDevice(configs.findById(serial).orElseThrow(), connectedDevices().get(serial), null);
    }

    public synchronized Device setDefault(String factoryCode, String serial) {
        validSerial(serial);
        String factory = factory(factoryCode);
        RfidPdaDevice config = configs.findById(serial)
                .orElseThrow(() -> new IllegalArgumentException("Reader not configured."));
        if (!assignedFactories(config).contains(factory))
            throw new IllegalArgumentException("Reader is not assigned to " + factory + ".");
        auditLogService.log(RequestActor.current(), "RFID_READER_DEFAULT_ASSIGNMENT",
                AppConstants.AuditResource.CARTON_LOADING, serial,
                "Default Reader for Factory=" + factory, null, null);
        clearDefaults(factory, serial);
        Set<String> defaults = defaultFactories(config);
        defaults.add(factory);
        updateAssignments(config, assignedFactories(config), defaults);
        configs.save(config);
        return asDevice(config, connectedDevices().get(serial), null);
    }

    /** Unassign the Factory; old multi-Factory records are handled safely during migration. */
    public synchronized void unassign(String factoryCode, String serial) {
        validSerial(serial);
        String factory = factory(factoryCode);
        if (captureRepository.existsById(serial)) throw new IllegalArgumentException("Release active carton capture first.");
        RfidPdaDevice config = configs.findById(serial)
                .orElseThrow(() -> new IllegalArgumentException("Reader not configured."));
        Set<String> assigned = assignedFactories(config);
        if (!assigned.remove(factory)) throw new IllegalArgumentException("Reader not assigned to " + factory + ".");
        auditLogService.log(RequestActor.current(), "RFID_READER_FACTORY_UNASSIGN",
                AppConstants.AuditResource.CARTON_LOADING, serial,
                "Removed Factory=" + factory + "; Remaining=" + assigned, null, null);
        Set<String> defaults = defaultFactories(config);
        defaults.remove(factory);
        updateAssignments(config, assigned, defaults);
        configs.save(config);
        ensureDefault(factory);
    }

    /** Delete only reader registration/settings, NEVER captured EPCs, RFID results or audit history. */
    public synchronized void deleteDevice(String serial) {
        validSerial(serial);
        if (captureRepository.existsById(serial))
            throw new IllegalArgumentException("Reader is reserved. STOP and release the active capture before deleting.");
        RfidPdaDevice config = configs.findById(serial)
                .orElseThrow(() -> new IllegalArgumentException("Reader configuration not found."));
        Set<String> previous = assignedFactories(config);
        for (String code : previous) factoryAccess.assertWeighingFactoryAccess(code);
        auditLogService.log(RequestActor.current(), "RFID_READER_CONFIGURATION_DELETE",
                AppConstants.AuditResource.CARTON_LOADING, serial,
                "Deleted reader registration; Factory assignments=" + previous
                        + "; RFID scan sessions and history preserved", null, null);
        configs.deleteById(serial);
        for (String code : previous) ensureDefault(code);
    }

    /** Preserve legacy single-factory records until each reader is next saved. */
    private Set<String> assignedFactories(RfidPdaDevice config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.getFactoryCodes() != null) for (String code : config.getFactoryCodes())
            if (code != null && code.matches("F[1-7]")) result.add(code);
        if (config.getFactoryCode() != null && config.getFactoryCode().toUpperCase(java.util.Locale.ROOT).matches("F[1-7]"))
            result.add(config.getFactoryCode().toUpperCase(java.util.Locale.ROOT));
        return result;
    }

    private Set<String> defaultFactories(RfidPdaDevice config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.getDefaultFactoryCodes() != null) for (String code : config.getDefaultFactoryCodes())
            if (code != null && code.matches("F[1-7]")) result.add(code);
        if (config.isDefaultReader() && config.getFactoryCode() != null
                && config.getFactoryCode().toUpperCase(java.util.Locale.ROOT).matches("F[1-7]"))
            result.add(config.getFactoryCode().toUpperCase(java.util.Locale.ROOT));
        result.retainAll(assignedFactories(config));
        return result;
    }

    private String normalizeFactory(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("F[1-7]")) throw new IllegalArgumentException("Factory must be F1 to F7.");
        return code;
    }

    private void updateAssignments(RfidPdaDevice config, Set<String> assigned, Set<String> defaults) {
        config.setFactoryCodes(new ArrayList<>(assigned));
        config.setDefaultFactoryCodes(new ArrayList<>(defaults));
        config.setFactoryCode(null); // clear old single-assignment field after migrating
        config.setDefaultReader(false);
    }

    private void clearDefaults(String factory, String exceptSerial) {
        for (RfidPdaDevice item : configs.findAll()) {
            if (!exceptSerial.equals(item.getSerial()) && defaultFactories(item).contains(factory)) {
                Set<String> defaults = defaultFactories(item);
                defaults.remove(factory);
                updateAssignments(item, assignedFactories(item), defaults);
                configs.save(item);
            }
        }
    }

    private void ensureDefault(String factory) {
        List<RfidPdaDevice> assigned = configs.findAll().stream()
                .filter(d -> assignedFactories(d).contains(factory))
                .sorted(Comparator.comparing(RfidPdaDevice::getSerial)).toList();
        if (assigned.isEmpty() || assigned.stream().anyMatch(d -> defaultFactories(d).contains(factory))) return;
        RfidPdaDevice first = assigned.get(0);
        Set<String> defaults = defaultFactories(first);
        defaults.add(factory);
        updateAssignments(first, assignedFactories(first), defaults);
        configs.save(first);
    }

    private Device asDevice(RfidPdaDevice config, String adbState, String viewFactory) {
        Capture current = currentCapture(config.getSerial());
        Set<String> assigned = assignedFactories(config);
        Set<String> defaults = defaultFactories(config);
        return new Device(config.getSerial(), config.getLabel(), config.getStationCode(),
                config.getStartX() != null && config.getStartY() != null && config.getStopX() != null && config.getStopY() != null,
                "device".equals(adbState), adbState == null ? "offline" : adbState,
                config.getStartX(), config.getStartY(), config.getStopX(), config.getStopY(),
                current != null, current == null ? "IDLE" : current.stopped() ? "STOPPED" : "STARTED",
                current == null ? null : current.sessionId(), viewFactory,
                viewFactory != null && defaults.contains(viewFactory),
                new ArrayList<>(assigned), new ArrayList<>(defaults), true);
    }

    public synchronized CaptureResult start(String serial, String stationCode, String orderId, String poId,
                                             String sscc, String sessionId) {
        assertCartonScanning(orderId, poId, sscc, sessionId, stationCode);
        RfidPdaDevice config = requireConfigured(serial, orderId, poId, sscc);
        requireOnline(serial);
        if (captureRepository.existsById(serial)) throw new IllegalArgumentException("Reader already reserved for a carton. Stop and import first.");
        if (captureRepository.existsBySessionId(sessionId))
            throw new IllegalArgumentException("This carton session is already reserved on another PDA.");
        // Snapshot file names BEFORE START. Modified/overwritten exports can still be
        // detected later by comparing their PDA modification timestamp with START.
        Set<String> baseline = new LinkedHashSet<>(filesOnDevice(serial));
        // Reserve atomically BEFORE sending the tap; unique indexes stop other JVMs
        // from acquiring this PDA or session concurrently. ADB failure removes reservation.
        // Use the PDA clock for the timestamp, not the backend server clock.
        // Export file modification timestamps are also read from that PDA.
        Instant pdaStart = deviceInstant(serial);
        // New PDA START = new logical attempt. Archive previous EPCs in MongoDB,
        // never mix them with reads from the next XLSX. No active PDA may hold it.
        RfidScanSession fresh = cartons.freshPdaCapture(orderId, poId, sscc, stationCode, sessionId);
        String activeSessionId = fresh.getId();
        Capture reserved = new Capture(station(stationCode), orderId, poId, sscc, activeSessionId,
                baseline, false, pdaStart);
        try {
            captureRepository.insert(encodeCapture(serial, reserved));
        } catch (DuplicateKeyException concurrent) {
            throw new IllegalArgumentException("RFID reader/carton is already reserved by another capture.");
        }
        try {
            tap(serial, config.getStartX(), config.getStartY());
        } catch (RuntimeException failure) {
            captureRepository.deleteById(serial);
            throw failure;
        }
        return new CaptureResult(serial, activeSessionId, "START_TAP_SENT",
                "New RFID capture started. Previous EPC list is archived and the current list is fresh.");
    }

    public synchronized CaptureResult stop(String serial, String stationCode, String orderId, String poId,
                                            String sscc, String sessionId) {
        Capture capture = requireCapture(serial, stationCode, orderId, poId, sscc, sessionId);
        if (capture.stopped()) return new CaptureResult(serial, sessionId, "STOP_TAP_SENT", "Already sent STOP.");
        RfidPdaDevice config = requireConfigured(serial, orderId, poId, sscc);
        requireOnline(serial);
        tap(serial, config.getStopX(), config.getStopY());
        captureRepository.save(encodeCapture(serial, new Capture(capture.station(), capture.order(), capture.po(), capture.sscc(),
                capture.sessionId(), capture.baselineFiles(), true, capture.startedAt())));
        return new CaptureResult(serial, sessionId, "STOP_TAP_SENT",
                "Stop tap sent. If RF88 did not export automatically, export XLSX on PDA before Preview.");
    }

    public synchronized Preview preview(String serial, String stationCode, String orderId, String poId,
                                        String sscc, String sessionId) {
        Capture capture = requireCapture(serial, stationCode, orderId, poId, sscc, sessionId);
        if (!capture.stopped()) throw new IllegalArgumentException("STOP the reader before loading its Excel data.");
        requireOnline(serial);
        assertCartonScanning(orderId, poId, sscc, sessionId, stationCode);
        // Determine capture ownership using the *same PDA clock* for START and mtime.
        // A reader may overwrite a pre-existing filename; filename-only comparison
        // misses those exports. A genuinely older unchanged file is never imported.
        List<String> newFiles = new ArrayList<>();
        Map<String, Long> timestamps = new LinkedHashMap<>();
        for (String file : filesOnDevice(serial)) {
            boolean existed = capture.baselineFiles().contains(file);
            long modified = fileModifiedEpoch(serial, file);
            long earliest = capture.startedAt().getEpochSecond() - (existed ? 0L : 3L);
            if (modified < earliest) continue;
            newFiles.add(file);
            timestamps.put(file, modified);
        }
        if (newFiles.isEmpty()) throw new IllegalArgumentException(
                "No inventory XLSX created/modified since this START. Export on the PDA, then Retry Load.");
        if (newFiles.size() > 30) throw new IllegalArgumentException(
                "Over 30 eligible XLSX exports; clear old files and rescan to avoid importing a wrong capture.");
        newFiles.sort(Comparator.comparingLong((String f) -> timestamps.get(f)).reversed()
                .thenComparing(Comparator.reverseOrder()));
        // RFID Control V2 can export several incremental snapshots. The latest
        // stable file is the authoritative final snapshot; do NOT merge old
        // snapshots or their EPCs with this capture.
        String selectedFile = newFiles.get(0);
        List<String> skippedFiles = new ArrayList<>(newFiles.subList(1, newFiles.size()));
        Path temp = null;
        try {
            String file = selectedFile;
            long sizeBefore = fileSize(serial, file);
            if (sizeBefore <= 0 || sizeBefore > 10 * 1024 * 1024L) {
                throw new IllegalArgumentException("Invalid inventory export size: " + file);
            }
            try { Thread.sleep(650L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for PDA export.", interrupted);
            }
            if (sizeBefore != fileSize(serial, file)) {
                throw new IllegalStateException("Inventory XLSX is still being written on PDA: " + file);
            }
            temp = Files.createTempFile("rf88-", ".xlsx");
            run(20, adb, "-s", serial, "pull", EXPORT_FOLDER + "/" + file, temp.toString());
            long sizeAfter = fileSize(serial, file);
            if (sizeBefore != sizeAfter || Files.size(temp) != sizeAfter) {
                throw new IllegalStateException("Inventory XLSX is still being written on PDA: " + file);
            }
            LinkedHashSet<String> accepted = new LinkedHashSet<>();
            LinkedHashSet<String> allRawTags = new LinkedHashSet<>();
            LinkedHashSet<String> rejectedRaw = new LinkedHashSet<>();
            List<String> actualReadEvents = new ArrayList<>();
            int epcCells = 0;
            int duplicates = 0;
            int rejected = 0;
            int reportedUnique = -1;
            int reportedReads = -1;
            try (InputStream input = Files.newInputStream(temp); Workbook workbook = WorkbookFactory.create(input)) {
                DataFormatter fmt = new DataFormatter();
                for (Sheet sheet : workbook) {
                    int headerRow = -1;
                    int epcColumn = -1;
                    for (Row row : sheet) {
                        if (row.getRowNum() > 35) break;
                        for (Cell cell : row) {
                            if ("EPC".equalsIgnoreCase(fmt.formatCellValue(cell).trim())) {
                                headerRow = row.getRowNum();
                                epcColumn = cell.getColumnIndex();
                                break;
                            }
                        }
                        if (headerRow >= 0) break;
                    }
                    if (headerRow < 0) continue; // Other non-inventory sheets have no EPC header.
                    int countColumn = -1;
                    Row labels = sheet.getRow(headerRow);
                    if (labels != null) for (Cell headerCell : labels) {
                        if ("COUNT".equalsIgnoreCase(fmt.formatCellValue(headerCell).trim())) {
                            countColumn = headerCell.getColumnIndex();
                            break;
                        }
                    }
                    if (reportedUnique < 0) reportedUnique = excelSummaryNumber(sheet, fmt, "UNIQUE TAG COUNT");
                    if (reportedReads < 0) reportedReads = excelSummaryNumber(sheet, fmt, "TOTAL READ COUNT");
                    for (int rowIndex = headerRow + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                        Row row = sheet.getRow(rowIndex);
                        if (row == null) continue;
                        Cell cell = row.getCell(epcColumn);
                        if (cell == null) continue;
                        String raw = fmt.formatCellValue(cell).replaceAll("\\s+", "").trim().toUpperCase(java.util.Locale.ROOT);
                        if (raw.isEmpty()) continue;
                        epcCells++;
                        int repetitions = 1;
                        if (countColumn >= 0) {
                            Cell countCell = row.getCell(countColumn);
                            if (countCell != null && !fmt.formatCellValue(countCell).trim().isEmpty()) {
                                try { repetitions = Integer.parseInt(fmt.formatCellValue(countCell).trim()); }
                                catch (NumberFormatException invalidCount) {
                                    throw new IllegalArgumentException("Invalid COUNT for EPC at Excel row " + (rowIndex + 1));
                                }
                            }
                        }
                        if (repetitions < 1 || repetitions > 10000 || actualReadEvents.size() + repetitions > 10000) {
                            throw new IllegalArgumentException("RFID Excel COUNT exceeds 10,000 reads; use a shorter capture.");
                        }
                        for (int repeat = 0; repeat < repetitions; repeat++) actualReadEvents.add(raw);
                        if (!allRawTags.add(raw)) duplicates++;
                        try {
                            accepted.add(TrueVueDecoder.decode(raw).epc());
                        } catch (IllegalArgumentException invalid) {
                            rejected++;
                            rejectedRaw.add(raw);
                        }
                        if (epcCells > 10000 || accepted.size() > 5000) {
                            throw new IllegalArgumentException("RFID export exceeds the safe size limit.");
                        }
                    }
                }
            }
            if (epcCells == 0) throw new IllegalArgumentException(
                    "RFID XLSX has no EPC column data. No reads were imported.");
            if (accepted.isEmpty()) throw new IllegalArgumentException(
                    "XLSX contains EPC rows but no valid TrueVUE EPC. Check raw tags in the export.");
            // Never silently present 4 valid tags as though the entire Excel had only 4.
            List<ExportFileSummary> summaries = List.of(new ExportFileSummary(file, timestamps.get(file),
                    epcCells, accepted.size(), duplicates, rejected));
            return new Preview(serial, sessionId, selectedFile, accepted.size(), new ArrayList<>(accepted),
                    0, capture.startedAt().getEpochSecond(), newFiles.size(), epcCells,
                    duplicates, rejected, summaries, reportedUnique, reportedReads,
                    allRawTags.size(), new ArrayList<>(rejectedRaw), skippedFiles, actualReadEvents);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read inventory XLSX: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cannot parse inventory XLSX: " + e.getMessage(), e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    /** RFID Control V2 exports summary counts above the EPC header in column B. */
    private int excelSummaryNumber(Sheet sheet, DataFormatter fmt, String title) {
        for (Row row : sheet) {
            if (row.getRowNum() > 35) break;
            for (Cell cell : row) {
                if (!title.equalsIgnoreCase(fmt.formatCellValue(cell).trim())) continue;
                Cell numberCell = row.getCell(cell.getColumnIndex() + 1);
                if (numberCell == null) return -1;
                try { return Integer.parseInt(fmt.formatCellValue(numberCell).trim().replace(",", "")); }
                catch (NumberFormatException invalid) { return -1; }
            }
        }
        return -1;
    }

    /** Emergency admin recovery after physically confirming Inventory is stopped. */
    public synchronized CaptureResult forceRelease(String serial, String reason, boolean inventoryStopped) {
        validSerial(serial);
        if (!inventoryStopped) throw new IllegalArgumentException("Confirm Inventory is stopped on the physical PDA first.");
        String cleaned = reason == null ? "" : reason.trim();
        if (cleaned.length() < 8 || cleaned.length() > 500)
            throw new IllegalArgumentException("Recovery reason must contain 8-500 characters.");
        RfidPdaCapture captured = captureRepository.findById(serial)
                .orElseThrow(() -> new IllegalArgumentException("No active capture to recover."));
        // Log first: never remove a reservation if the audit trail cannot be saved.
        auditLogService.log(RequestActor.current(), "RFID_PDA_FORCE_RELEASE", AppConstants.AuditResource.CARTON_LOADING,
                captured.getSessionId(), "Admin physically confirmed stop. PDA=" + serial + " carton=" + captured.getSscc()
                        + "; reason=" + cleaned, null, null);
        captureRepository.deleteById(serial);
        return new CaptureResult(serial, captured.getSessionId(), "RELEASED_BY_ADMIN",
                "Reservation released; re-check the carton session before further scanning.");
    }

    /** Release only after the frontend's existing /reads endpoint acknowledged EPC ingestion. */
    public synchronized CaptureResult release(String serial, String stationCode, String orderId, String poId,
                                               String sscc, String sessionId) {
        Capture capture = requireCapture(serial, stationCode, orderId, poId, sscc, sessionId);
        if (!capture.stopped()) throw new IllegalArgumentException("STOP device first.");
        captureRepository.deleteById(serial);
        return new CaptureResult(serial, sessionId, "RELEASED", "Reader ready for another carton.");
    }

    private RfidPdaDevice requireConfigured(String serial, String orderId, String poId, String sscc) {
        validSerial(serial);
        RfidPdaDevice config = configs.findById(serial)
                .orElseThrow(() -> new IllegalArgumentException("Configure this reader for the station first."));
        String cartonFactory = factory(cartons.factoryForCarton(orderId, poId, sscc));
        Set<String> readerFactories = assignedFactories(config);
        if (!readerFactories.contains(cartonFactory))
            throw new IllegalArgumentException("RFID reader is not assigned to carton Factory " + cartonFactory + ".");
        point(config.getStartX(), config.getStartY()); point(config.getStopX(), config.getStopY());
        return config;
    }
    private Capture requireCapture(String serial, String stationCode, String orderId, String poId,
                                   String sscc, String sessionId) {
        validSerial(serial);
        requireConfigured(serial, orderId, poId, sscc);
        Capture capture = currentCapture(serial);
        if (capture == null || !capture.station().equalsIgnoreCase(station(stationCode))
                || !capture.order().equals(orderId) || !capture.po().equals(poId)
                || !capture.sscc().equals(sscc) || !capture.sessionId().equals(sessionId))
            throw new IllegalArgumentException("Reader capture does not belong to this carton/session. Rescan required.");
        return capture;
    }
    private void assertCartonScanning(String orderId, String poId, String sscc, String sessionId, String stationCode) {
        RfidScanSession latest = cartons.latest(orderId, poId, sscc); // validates carton, PO and factory access
        if (latest == null || !"SCANNING".equals(latest.getStatus()) || !latest.getId().equals(sessionId))
            throw new IllegalArgumentException("Carton RFID session is not SCANNING or is stale.");
        if (!station(stationCode).equalsIgnoreCase(latest.getStationCode()))
            throw new IllegalArgumentException("Session belongs to another station.");
    }
    private String factory(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("F[1-7]")) throw new IllegalArgumentException("Factory must be F1 to F7.");
        factoryAccess.assertWeighingFactoryAccess(code);
        return code;
    }
    private String station(String value) {
        if (value == null || !value.trim().matches("[A-Za-z0-9_-]{1,50}"))
            throw new IllegalArgumentException("Station code is required (letters, numbers, dash, underscore).");
        return value.trim();
    }
    private void point(Integer x, Integer y) {
        if (x == null || y == null || x < 0 || y < 0 || x > 10000 || y > 10000)
            throw new IllegalArgumentException("Configure both START and STOP screen X/Y coordinates (0-10000).");
    }
    private void validSerial(String serial) {
        if (serial == null || !SERIAL_PATTERN.matcher(serial).matches())
            throw new IllegalArgumentException("Invalid ADB serial.");
    }
    private void tap(String serial, Integer x, Integer y) {
        run(10, adb, "-s", serial, "shell", "input", "tap", x.toString(), y.toString());
    }
    private void requireOnline(String serial) {
        if (!"device".equals(connectedDevices().get(serial)))
            throw new IllegalArgumentException("PDA is offline or USB debugging is not authorized.");
    }
    private Map<String, String> connectedDevices() {
        String output = run(10, adb, "devices");
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length >= 2 && SERIAL_PATTERN.matcher(cols[0]).matches()
                    && ("device".equals(cols[1]) || "offline".equals(cols[1]) || "unauthorized".equals(cols[1]))) {
                result.put(cols[0], cols[1]);
            }
        }
        return result;
    }
    private Instant deviceInstant(String serial) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(
                    run(10, adb, "-s", serial, "shell", "date", "+%s").trim()));
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Cannot read PDA clock; refusing to start untraceable capture.", error);
        }
    }

    private long fileModifiedEpoch(String serial, String filename) {
        // filename must have passed the strict inventory_YYYYMMDD_HHMMSS.xlsx pattern.
        if (!EXCEL_PATTERN.matcher(filename).matches()) throw new IllegalArgumentException("Invalid export filename.");
        try {
            return Long.parseLong(run(10, adb, "-s", serial, "shell", "stat", "-c", "%Y",
                    EXPORT_FOLDER + "/" + filename).trim());
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Cannot check PDA export modification time; import denied.", error);
        }
    }

    private long fileSize(String serial, String filename) {
        if (!EXCEL_PATTERN.matcher(filename).matches()) throw new IllegalArgumentException("Invalid export filename.");
        try {
            return Long.parseLong(run(10, adb, "-s", serial, "shell", "stat", "-c", "%s",
                    EXPORT_FOLDER + "/" + filename).trim());
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Cannot check PDA export size.", error);
        }
    }

    private List<String> filesOnDevice(String serial) {
        String result = run(10, adb, "-s", serial, "shell", "ls", "-1", EXPORT_FOLDER);
        List<String> files = new ArrayList<>();
        for (String line : result.split("\\R")) {
            String file = line.trim();
            if (EXCEL_PATTERN.matcher(file).matches()) files.add(file);
        }
        return files;
    }
    private String run(int timeoutSeconds, String... arguments) {
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            Process active = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = active.getInputStream()) {
                    byte[] buffer = new byte[2048];
                    int n;
                    while ((n = in.read(buffer)) != -1 && output.size() < 1024 * 1024) output.write(buffer, 0, n);
                } catch (IOException ignored) { }
            }, "rf88-adb-output");
            reader.setDaemon(true); reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("ADB command timed out.");
            }
            reader.join(1200);
            String text = output.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("ADB failed: " + text.trim());
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("ADB is unavailable on the backend host. Configure lululemon.rfid.adb-path: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ADB command interrupted.", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
