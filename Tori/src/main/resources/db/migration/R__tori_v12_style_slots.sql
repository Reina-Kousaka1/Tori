-- Repeatable additive V12 style slot support; all existing loadout slots remain valid.
ALTER TABLE economy_v2_loadout
  DROP CONSTRAINT IF EXISTS economy_v2_loadout_slot_check;
ALTER TABLE economy_v2_loadout
  ADD CONSTRAINT economy_v2_loadout_slot_check
  CHECK (slot IN ('top','bottom','dress','outerwear','shoes','bag',
                 'accessory','jewelry','hair_accessory','necklace','earrings'));
