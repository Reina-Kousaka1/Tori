// Manual mongosh preparation only. Never invoked by Tori startup.
// Does not import, delete or modify existing documents.
if (process.env.TORI_MONGO_PREPARE !== 'YES') {
    throw new Error('Preparation disabled. Explicitly set TORI_MONGO_PREPARE=YES to create indexes.');
}
const databaseName = process.env.MONGODB_DATABASE;
if (!databaseName || !/^[A-Za-z][A-Za-z0-9_-]{0,62}$/.test(databaseName)
    || ['admin', 'local', 'config'].includes(databaseName.toLowerCase())) {
    throw new Error('Set MONGODB_DATABASE to a dedicated Tori database.');
}
const target = db.getSiblingDB(databaseName);
const collections = [
    ['bot_stats_context', {bot_id: 1, guild_id: 1, channel_id: 1}],
    ['bot_events', {session_id: 1, event_type: 1}],
    ['guild_prefixes', {guild_id: 1}],
    ['moderation_cases', {guild_id: 1, case_id: 1}],
    ['guild_languages', {guild_id: 1}],
];
for (const [name, key] of collections) {
    target.getCollection(name).createIndex(key, {unique: true, name: 'natural_key_v1'});
}
target.moderation_cases.createIndex({guild_id: 1, occurred_at: 1}, {name: 'guild_time_v1'});
print('Tori indexes prepared. No data imported.');
