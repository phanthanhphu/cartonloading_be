package org.bsl.cartonloading.buyers.lululemon.service;

import java.util.List;

/** Business conflict: return only ownership contact information, never other Factory's PO data. */
public class PoImportConflictException extends RuntimeException {
    private final List<PoIdentityRegistryService.Conflict> duplicates;
    public PoImportConflictException(List<PoIdentityRegistryService.Conflict> duplicates) {
        super("Import blocked: " + duplicates.size() + " PO identity conflict(s). Review the existing Factory and importer in the conflict details. To edit or delete existing POs, use the PO management screen.");
        this.duplicates = List.copyOf(duplicates);
    }
    public List<PoIdentityRegistryService.Conflict> getDuplicates() { return duplicates; }
}
