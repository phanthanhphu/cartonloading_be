/**
 * Lululemon MongoDB collection inventory (mongosh only).
 *
 * Default: READ ONLY. Checks collection names and approximate document counts.
 * The only approved retired collection is print_handoffs (legacy Print Request).
 *
 * OPTIONAL: Change DROP_EMPTY_LEGACY_PRINT_HANDOFFS to true to DROP *only* an EMPTY
 * print_handoffs collection on a database whose name explicitly contains test/dev.
 * Backup first. The script NEVER deletes non-empty data or unknown collections.
 *
 * Example: mongosh "mongodb://localhost:27017/cartonloading_test" --file review-mongodb-collections.mongosh.js
 */
const DROP_EMPTY_LEGACY_PRINT_HANDOFFS = false;
const ACTIVE_COLLECTIONS = new Set([
  'users', 'departments', 'buyers', 'audit_logs',
  'orders', 'packing_allocation_lines', 'packing_list_lines',
  'carton_operations', 'factory_barcodes', 'scale_stations', 'shipments',
  'purchase_orders', 'cartons', 'carton_items', 'scan_events',
  'weighing_profiles', 'weighing_orders', 'weighing_results',
  'shipping_schedules', 'lululemon_carton_checking_history',
  'lululemon_rfid_scan_sessions'
]);
const RETIRED_COLLECTIONS = new Set(['print_handoffs']);
const databaseName = db.getName();
print(`MongoDB collection audit in database: ${databaseName}`);
const names = db.getCollectionNames().sort();
for (const name of names) {
  const type = ACTIVE_COLLECTIONS.has(name) ? 'ACTIVE' : RETIRED_COLLECTIONS.has(name) ? 'RETIRED' : 'UNKNOWN: REVIEW';
  const count = db.getCollection(name).estimatedDocumentCount();
  print(`${name.padEnd(38)} ${String(count).padStart(8)} docs  ${type}`);
}
const unknown = names.filter((name) => !ACTIVE_COLLECTIONS.has(name) && !RETIRED_COLLECTIONS.has(name) && !name.startsWith('system.'));
if (unknown.length) print(`Review these unknown collections manually; they were not auto-deleted: ${unknown.join(', ')}`);
if (names.includes('print_handoffs')) {
  const legacyCount = db.getCollection('print_handoffs').countDocuments({});
  if (!DROP_EMPTY_LEGACY_PRINT_HANDOFFS) {
    print(`Legacy print_handoffs has ${legacyCount} documents; no change made (read-only mode).`);
  } else if (!/(^|[-_])(test|dev)([-_]|$)/i.test(databaseName)) {
    throw new Error('Refusing deletion: database name must clearly indicate test/dev.');
  } else if (legacyCount !== 0) {
    throw new Error('Refusing deletion: print_handoffs is not empty. Back up and review records manually.');
  } else {
    db.getCollection('print_handoffs').drop();
    print('Dropped EMPTY legacy print_handoffs collection in a test/dev database.');
  }
}
