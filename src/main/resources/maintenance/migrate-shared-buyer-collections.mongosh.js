/**
 * MongoDB one-database, buyer-neutral collection migration.
 * BACKUP FIRST; stop all writers (Spring Boot and device services) while applying.
 * Dry-run by default. Change APPLY to true ONLY after reviewing the output.
 * Run with: mongosh "mongodb://localhost:27017/cartonloading" --file migrate-shared-buyer-collections.mongosh.js
 * The application must NOT be started with the new collection names before this migration.
 * Never rename the MongoDB database and never remove any other buyer's documents.
 */
const APPLY = false;
const aliases = Object.freeze({ES: 'ENGELBERT_STRAUSS', ENGELBERTSTRAUSS: 'ENGELBERT_STRAUSS'});
const rename = [
  ['lululemon_po_pick_claims', 'po_pick_claims'],
  ['lululemon_po_pick_history', 'po_pick_history'],
  ['lululemon_rfid_scan_sessions', 'rfid_scan_sessions'],
  ['lululemon_rfid_pda_captures', 'rfid_pda_captures'],
  ['lululemon_rfid_pda_devices', 'rfid_pda_devices'],
  ['lululemon_carton_checking_history', 'carton_checking_history']
];
const buyerOwned = [
  'orders', 'packing_allocation_lines', 'packing_list_lines', 'carton_operations',
  'shipments', 'purchase_orders', 'cartons', 'carton_items', 'scan_events',
  'weighing_profiles', 'weighing_orders', 'weighing_results', 'shipping_schedules',
  'carton_checking_history', 'po_pick_claims', 'po_pick_history',
  'rfid_scan_sessions', 'rfid_pda_captures', 'rfid_pda_devices', 'scale_plc_devices'
];
const existing = new Set(db.getCollectionNames());
const overlaps = rename.filter(([oldName, newName]) => existing.has(oldName) && existing.has(newName));
if (overlaps.length) {
  throw new Error('ABORTED: both old AND new collections exist. Resolve duplicates manually after backup: ' + JSON.stringify(overlaps));
}
print(`Database ${db.getName()}  APPLY=${APPLY}. Neither database nor other-buyer data will be deleted.`);
rename.forEach(([oldName, newName]) => {
  if (!existing.has(oldName)) { print(`SKIP ${oldName} (not found)`); return; }
  const count = db.getCollection(oldName).countDocuments({});
  print(`${APPLY ? 'RENAME' : 'WOULD RENAME'} ${oldName} -> ${newName} (${count} documents)`);
  if (APPLY) { db.getCollection(oldName).renameCollection(newName, false); existing.delete(oldName); existing.add(newName); }
});
if (!APPLY) { print('DRY RUN: buyer_key backfill shown below; no database writes will occur.'); }
for (const collection of buyerOwned) {
  if (!existing.has(collection)) continue;
  const c = db.getCollection(collection);
  // Only buyer-specific fields; organization-wide records (Users, Departments, Scale stations)
  // intentionally are NOT assigned an artificial buyer.
  const buyers = c.distinct('buyerCode', {buyerCode: {$type: 'string'}});
  for (const raw of buyers) {
    const canonical = String(raw).trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_');
    const key = aliases[canonical] || canonical;
    if (!key) continue;
    const selector = {buyerCode: raw, $or: [{buyer_key: {$exists:false}}, {buyer_key: null}, {buyer_key:''}]};
    const count = c.countDocuments(selector);
    if (count) {
      print(`${APPLY?'BACKFILL':'WOULD BACKFILL'} ${collection} buyerCode=${raw} -> buyer_key=${key}, ${count} rows`);
      if (APPLY) c.updateMany(selector, {$set: {buyer_key: key}});
    }
  }
  // Historical collections that originally existed only for LULULEMON use buyer_key but no buyerCode.
  if (['po_pick_history','rfid_pda_captures','rfid_pda_devices'].includes(collection)) {
    const selector = {$or: [{buyer_key: {$exists: false}}, {buyer_key: null}, {buyer_key:''}]};
    const count = c.countDocuments(selector);
    if (count) {
      print(`${APPLY?'BACKFILL':'WOULD BACKFILL'} historical ${collection}: ${count} LULULEMON rows`);
      if (APPLY) c.updateMany(selector, {$set: {buyer_key:'LULULEMON'}});
    }
  }
}
if (existing.has('factory_barcodes')) {
  const barcodeCollection = db.getCollection('factory_barcodes');
  for (const raw of barcodeCollection.distinct('assignedBuyerCode', {assignedBuyerCode: {$type:'string'}})) {
    const canonical = String(raw).trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_');
    const key = aliases[canonical] || canonical;
    const selector = {assignedBuyerCode: raw, $or: [{buyer_key: {$exists:false}}, {buyer_key:null}, {buyer_key:''}]};
    const count = barcodeCollection.countDocuments(selector);
    if (count && key) {
      print(`${APPLY?'BACKFILL':'WOULD BACKFILL'} factory_barcodes ${raw}: ${count} assigned rows`);
      if (APPLY) barcodeCollection.updateMany(selector, {$set: {buyer_key:key}});
    }
  }
}
print(APPLY ? 'Migration applied. Review collection counts and verify endpoints before restoring traffic.' : 'NO CHANGES MADE. Back up, stop writers, change APPLY to true, then rerun.');
