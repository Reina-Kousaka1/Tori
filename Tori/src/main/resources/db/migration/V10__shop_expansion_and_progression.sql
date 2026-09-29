-- Expand the original catalog and make the already-present progression model usable.
-- Additive only: existing items, balances and inventory are left in place.
ALTER TABLE economy_v2_catalog_items
    ADD COLUMN cosmetic_slots TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN career_requirement TEXT REFERENCES economy_v2_careers(career_code) ON DELETE RESTRICT,
    ADD COLUMN career_level_requirement INTEGER NOT NULL DEFAULT 1;
ALTER TABLE economy_v2_catalog_items
    ADD CONSTRAINT catalog_career_level_positive CHECK (career_level_requirement > 0);

INSERT INTO economy_v2_catalog_items
    (item_id,name,description,category,subcategory,rarity,buy_price,sell_price,
     rotation_weight,season,stackable,max_stack,consumable,tradeable,equip_slots,
     conflict_slots,tags,level_requirement,cosmetic_slots)
SELECT id,name,description,category,subcategory,rarity,price,greatest(1,price/2),
       weight,season,stackable,max_stack,false,true,equip,conflicts,tags,level,cosmetics
FROM (VALUES
 ('ribbon_baby_tee','Ribbon Baby Tee','A fitted cotton tee with a tiny ribbon detail.','fashion','baby_tees','common',78,100,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['y2k','bows'],1,ARRAY[]::text[]),
 ('cherry_gingham_cami','Cherry Gingham Cami','A light gingham cami with a cherry print.','fashion','camis','uncommon',96,48,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['cherries','summer'],1,ARRAY[]::text[]),
 ('lace_trim_tube_top','Lace Trim Tube Top','A soft tube top finished with delicate lace.','fashion','tube_tops','uncommon',105,52,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['lace','y2k'],1,ARRAY[]::text[]),
 ('berry_fitted_tee','Berry Fitted Tee','An easy fitted tee in a deep berry tone.','fashion','fitted_tees','common',72,100,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['basic'],1,ARRAY[]::text[]),
 ('cloud_long_sleeve','Cloud Long Sleeve','A soft long-sleeve top for cool afternoons.','fashion','long_sleeves','common',88,100,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['cozy'],1,ARRAY[]::text[]),
 ('star_off_shoulder','Star Off-Shoulder Top','A relaxed off-shoulder top with tiny stars.','fashion','off_shoulder','uncommon',118,40,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['stars','y2k'],1,ARRAY[]::text[]),
 ('rose_bodysuit','Rose Ribbed Bodysuit','A simple ribbed bodysuit in muted rose.','fashion','bodysuits','uncommon',135,35,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['basic'],1,ARRAY[]::text[]),
 ('leopard_wrap_top','Leopard Wrap Top','An original animal-print wrap top.','fashion','wrap_tops','rare',185,14,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['animal_print','glam'],3,ARRAY[]::text[]),
 ('cropped_cable_knit','Cropped Cable Knit','A cropped cable-knit layer in cream.','fashion','knit_tops','uncommon',128,38,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['cozy'],1,ARRAY[]::text[]),
 ('lavender_knit_polo','Lavender Knit Polo','A soft knit polo with a neat collar.','fashion','knit_tops','uncommon',142,35,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['knit'],1,ARRAY[]::text[]),
 ('plum_oversized_hoodie','Plum Oversized Hoodie','A roomy hoodie for late-night study breaks.','fashion','hoodies','common',148,80,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['cozy'],1,ARRAY[]::text[]),
 ('silver_zip_hoodie','Silver Zip Hoodie','A light zip hoodie with subtle metallic details.','fashion','zip_hoodies','rare',220,12,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['y2k','rhinestones'],3,ARRAY[]::text[]),
 ('bow_collar_cardigan','Bow Collar Cardigan','A button cardigan with a soft bow collar.','fashion','cardigans','uncommon',174,32,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['bows','cozy'],1,ARRAY[]::text[]),
 ('soft_mocha_sweater','Soft Mocha Sweater','A warm everyday sweater in a calm neutral shade.','fashion','sweaters','common',138,75,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['cozy','basic'],1,ARRAY[]::text[]),
 ('black_pleated_mini','Black Pleated Mini Skirt','A clean pleated skirt with a comfortable waist.','fashion','mini_skirts','common',122,85,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['y2k'],1,ARRAY[]::text[]),
 ('plaid_pleated_skirt','Plaid Pleated Skirt','A soft plaid skirt for everyday outfits.','fashion','pleated_skirts','uncommon',145,38,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['school_style'],1,ARRAY[]::text[]),
 ('rose_denim_skirt','Rose Denim Mini Skirt','Washed denim with a muted rose tint.','fashion','denim_skirts','uncommon',139,40,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['denim'],1,ARRAY[]::text[]),
 ('sporty_track_shorts','Sporty Track Shorts','Lightweight shorts with a contrast side stripe.','fashion','shorts','common',82,100,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['sporty'],1,ARRAY[]::text[]),
 ('indigo_flared_jeans','Indigo Flared Jeans','Classic indigo denim with a gentle flare.','fashion','flared_jeans','common',162,80,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['denim'],1,ARRAY[]::text[]),
 ('washed_black_baggy','Washed Black Baggy Jeans','Relaxed washed-black denim.','fashion','baggy_jeans','uncommon',172,36,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['denim','streetwear'],1,ARRAY[]::text[]),
 ('cream_straight_jeans','Cream Straight-Leg Jeans','Straight-leg denim in a soft cream color.','fashion','straight_jeans','uncommon',168,36,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['denim'],1,ARRAY[]::text[]),
 ('rose_cargo_trousers','Rose Cargo Trousers','Relaxed cargo trousers with practical pockets.','fashion','cargo_pants','uncommon',178,34,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['streetwear'],1,ARRAY[]::text[]),
 ('ribbed_flare_leggings','Ribbed Flare Leggings','Stretchy flares for a comfortable day.','fashion','leggings','common',95,90,NULL,true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['cozy','sporty'],1,ARRAY[]::text[]),
 ('blueberry_day_dress','Blueberry Day Dress','A casual short-sleeve dress in dusty blue.','fashion','casual_dresses','uncommon',186,30,NULL,true,20,false,ARRAY['dress'],ARRAY['top','bottom'],ARRAY['casual'],1,ARRAY[]::text[]),
 ('rose_ribbed_dress','Rose Ribbed Knit Dress','A fitted knit dress in a soft rose shade.','fashion','knit_dresses','rare',235,12,NULL,true,20,false,ARRAY['dress'],ARRAY['top','bottom'],ARRAY['knit','glam'],3,ARRAY[]::text[]),
 ('midnight_party_dress','Midnight Party Dress','A simple evening dress with a subtle sheen.','fashion','party_dresses','special',310,5,NULL,true,20,false,ARRAY['dress'],ARRAY['top','bottom'],ARRAY['glam'],5,ARRAY[]::text[]),
 ('spring_flower_dress','Spring Flower Dress','A light seasonal dress with an original flower pattern.','seasonal','dresses','uncommon',192,28,'spring',true,20,false,ARRAY['dress'],ARRAY['top','bottom'],ARRAY['spring'],1,ARRAY[]::text[]),
 ('cropped_bomber_jacket','Cropped Bomber Jacket','A light sporty jacket with ribbed cuffs.','fashion','jackets','uncommon',205,28,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['sporty','streetwear'],1,ARRAY[]::text[]),
 ('cream_denim_jacket','Cream Denim Jacket','An easy washed-denim layer in cream.','fashion','jackets','common',180,40,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['denim'],1,ARRAY[]::text[]),
 ('cozy_puffer_jacket','Cozy Puffer Jacket','A warm jacket for crisp winter days.','fashion','jackets','rare',260,10,'winter',true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['cozy','winter'],3,ARRAY[]::text[]),
 ('pink_platform_sneakers','Pink Platform Sneakers','Everyday platforms with soft pink accents.','fashion','platform_sneakers','uncommon',198,30,NULL,true,20,false,ARRAY['shoes'],ARRAY[]::text[],ARRAY['y2k'],1,ARRAY[]::text[]),
 ('cream_ballet_flats','Cream Ballet Flats','Simple flats with a soft ribbon strap.','fashion','ballet_flats','uncommon',154,38,NULL,true,20,false,ARRAY['shoes'],ARRAY[]::text[],ARRAY['balletcore'],1,ARRAY[]::text[]),
 ('chestnut_ankle_boots','Chestnut Ankle Boots','A sturdy ankle boot in warm chestnut.','fashion','ankle_boots','uncommon',210,26,'autumn',true,20,false,ARRAY['shoes'],ARRAY[]::text[],ARRAY['autumn'],1,ARRAY[]::text[]),
 ('leopard_mini_bag','Leopard Mini Bag','A small original animal-print shoulder bag.','accessories','mini_bags','rare',165,15,NULL,true,20,false,ARRAY['bag'],ARRAY[]::text[],ARRAY['animal_print','y2k'],2,ARRAY[]::text[]),
 ('cherry_bead_necklace','Cherry Bead Necklace','A playful necklace with tiny cherry beads.','accessories','necklaces','uncommon',105,35,NULL,true,20,false,ARRAY['jewelry'],ARRAY[]::text[],ARRAY['cherries'],1,ARRAY[]::text[]),
 ('golden_heart_bracelet','Golden Heart Bracelet','A slim bracelet with a small heart charm.','accessories','bracelets','common',78,80,NULL,true,20,false,ARRAY['jewelry'],ARRAY[]::text[],ARRAY['hearts'],1,ARRAY[]::text[]),
 ('pearl_star_earrings','Pearl Star Earrings','Small star earrings with a pearl-like finish.','accessories','earrings','rare',126,18,NULL,true,20,false,ARRAY['jewelry'],ARRAY[]::text[],ARRAY['stars'],2,ARRAY[]::text[]),
 ('tiny_bow_ring','Tiny Bow Ring','A delicate ring with an original bow detail.','accessories','rings','uncommon',88,34,NULL,true,20,false,ARRAY['jewelry'],ARRAY[]::text[],ARRAY['bows'],1,ARRAY[]::text[]),
 ('heart_buckle_belt','Heart Buckle Belt','A simple belt with a heart-shaped buckle.','accessories','belts','uncommon',92,35,NULL,true,20,false,ARRAY['accessory'],ARRAY[]::text[],ARRAY['hearts','y2k'],1,ARRAY[]::text[]),
 ('rose_tinted_sunglasses','Rose Tinted Sunglasses','Light rose-tinted lenses in a slim frame.','accessories','glasses','uncommon',110,32,'summer',true,20,false,ARRAY['accessory'],ARRAY[]::text[],ARRAY['summer'],1,ARRAY[]::text[]),
 ('velvet_bow_barrette','Velvet Bow Barrette','A soft velvet bow for an everyday hairstyle.','accessories','bows','common',56,90,NULL,true,20,false,ARRAY['hair_accessory'],ARRAY[]::text[],ARRAY['bows'],1,ARRAY[]::text[]),
 ('star_snap_clips','Star Snap Clips','A pair of simple metallic star clips.','accessories','hair_clips','common',48,95,NULL,true,20,false,ARRAY['hair_accessory'],ARRAY[]::text[],ARRAY['stars'],1,ARRAY[]::text[]),
 ('glossy_shoulder_pouch','Glossy Shoulder Pouch','A compact glossy pouch with a short strap.','accessories','shoulder_bags','uncommon',132,30,NULL,true,20,false,ARRAY['bag'],ARRAY[]::text[],ARRAY['y2k'],1,ARRAY[]::text[]),
 ('soft_lilac_nail_lacquer','Soft Lilac Nail Lacquer','A permanent profile cosmetic color unlock.','beauty','nails','uncommon',98,32,NULL,false,1,false,ARRAY[]::text[],ARRAY[]::text[],ARRAY['cosmetic'],1,ARRAY['nails']),
 ('glitter_nail_palette','Glitter Nail Palette','A permanent profile cosmetic nail style.','beauty','nails','rare',158,14,NULL,false,1,false,ARRAY[]::text[],ARRAY[]::text[],ARRAY['cosmetic','glam'],2,ARRAY['nails']),
 ('soft_rose_makeup','Soft Rose Makeup Style','A permanent soft-rose profile cosmetic.','beauty','makeup','uncommon',145,28,NULL,false,1,false,ARRAY[]::text[],ARRAY[]::text[],ARRAY['cosmetic'],1,ARRAY['makeup']),
 ('starlight_eye_style','Starlight Eye Style','A permanent profile makeup customization.','beauty','makeup','rare',188,12,NULL,false,1,false,ARRAY[]::text[],ARRAY[]::text[],ARRAY['cosmetic','glam'],3,ARRAY['makeup']),
 ('moonlit_hair_pin','Moonlit Hair Pin','A permanent profile hair accessory selection.','beauty','hair','uncommon',112,28,NULL,false,1,false,ARRAY[]::text[],ARRAY[]::text[],ARRAY['cosmetic'],1,ARRAY['hair_accessory']),
 ('vanilla_scent_collectible','Vanilla Scent Collectible','A small collectible inspired by a warm vanilla scent.','beauty','perfume','rare',205,10,NULL,false,1,false,ARRAY[]::text[],ARRAY[]::text[],ARRAY['collectible'],2,ARRAY[]::text[]),
 ('halloween_velvet_capelet','Halloween Velvet Capelet','A seasonal soft capelet for autumn evenings.','seasonal','outerwear','rare',248,9,'halloween',true,10,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['halloween','autumn'],3,ARRAY[]::text[]),
 ('valentine_cherry_clips','Valentine Cherry Clips','Seasonal cherry hair clips in a heart-day colorway.','seasonal','hair_clips','uncommon',82,26,'valentines',true,20,false,ARRAY['hair_accessory'],ARRAY[]::text[],ARRAY['valentines','cherries'],1,ARRAY[]::text[]),
 ('summer_sporty_skirt','Summer Sporty Skirt','A light skirt for warm weather and practice.','seasonal','skirts','uncommon',128,30,'summer',true,20,false,ARRAY['bottom'],ARRAY['dress'],ARRAY['summer','sporty'],1,ARRAY[]::text[]),
 ('autumn_ribbon_sweater','Autumn Ribbon Sweater','A cozy sweater with a small ribbon accent.','seasonal','sweaters','uncommon',158,26,'autumn',true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['autumn','bows'],1,ARRAY[]::text[]),
 ('winter_pearl_mittens','Winter Pearl Mittens','Soft seasonal mittens with subtle pearl-like details.','seasonal','accessories','uncommon',86,25,'winter',true,20,false,ARRAY['accessory'],ARRAY[]::text[],ARRAY['winter'],1,ARRAY[]::text[]),
 ('spring_blossom_bag','Spring Blossom Bag','A compact bag with a small blossom motif.','seasonal','bags','rare',172,12,'spring',true,10,false,ARRAY['bag'],ARRAY[]::text[],ARRAY['spring'],2,ARRAY[]::text[]),
 ('ballet_lace_leotard','Ballet Lace Leotard','A rehearsal leotard with a modest lace panel.','ballet','leotards','uncommon',174,30,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['balletcore','lace'],1,ARRAY[]::text[]),
 ('ballet_warmup_wrap','Ballet Warm-up Wrap','A light wrap for pre-class warm-ups.','ballet','warmups','common',92,80,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['balletcore'],1,ARRAY[]::text[]),
 ('volleyball_mesh_layer','Volleyball Mesh Layer','A breathable layer for court warm-ups.','volleyball','tops','uncommon',118,34,NULL,true,20,false,ARRAY['top'],ARRAY['dress'],ARRAY['sporty'],1,ARRAY[]::text[]),
 ('volleyball_knee_sleeves','Volleyball Knee Sleeves','A pair of soft sleeves for practice.','volleyball','accessories','common',88,75,NULL,true,20,false,ARRAY['accessory'],ARRAY[]::text[],ARRAY['sporty'],1,ARRAY[]::text[]),
 ('cheer_warmup_jacket','Cheer Warm-up Jacket','A team-inspired jacket for practice days.','cheer','outerwear','uncommon',196,26,NULL,true,20,false,ARRAY['outerwear'],ARRAY[]::text[],ARRAY['cheer','sporty'],1,ARRAY[]::text[]),
 ('cheer_ribbon_bracelet','Cheer Ribbon Bracelet','A small team-color ribbon bracelet.','cheer','accessories','common',62,78,NULL,true,20,false,ARRAY['jewelry'],ARRAY[]::text[],ARRAY['cheer'],1,ARRAY[]::text[])
) AS seed(id,name,description,category,subcategory,rarity,price,weight,season,stackable,max_stack,consumable,equip,conflicts,tags,level,cosmetics)
ON CONFLICT (item_id) DO NOTHING;

-- A configurable-in-data progression curve. XP and unlocks stay separate from Credits.
INSERT INTO economy_v2_xp_thresholds(level,required_xp)
SELECT level, CASE WHEN level=1 THEN 0 ELSE (level-1)::bigint*(level-1)*100 END
FROM generate_series(1,50) AS levels(level)
ON CONFLICT (level) DO NOTHING;

ALTER TABLE economy_v2_careers ADD COLUMN description TEXT NOT NULL DEFAULT '';
UPDATE economy_v2_careers SET description=CASE career_code
  WHEN 'ballet' THEN 'Build technique through regular ballet practice.'
  WHEN 'volleyball' THEN 'Grow court skills through training and match preparation.'
  WHEN 'cheer' THEN 'Develop routines, timing and team leadership.'
  ELSE description END
WHERE description='';

CREATE TABLE economy_v2_career_policy (
    policy_id SMALLINT PRIMARY KEY CHECK (policy_id=1),
    switch_cooldown_hours INTEGER NOT NULL CHECK (switch_cooldown_hours BETWEEN 0 AND 8760)
);
INSERT INTO economy_v2_career_policy(policy_id,switch_cooldown_hours) VALUES (1,24)
ON CONFLICT (policy_id) DO NOTHING;

CREATE TABLE economy_v2_career_selections (
    user_id TEXT PRIMARY KEY REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    career_code TEXT NOT NULL REFERENCES economy_v2_careers(career_code) ON DELETE RESTRICT,
    selected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE economy_v2_career_actions (
    career_code TEXT NOT NULL REFERENCES economy_v2_careers(career_code) ON DELETE RESTRICT,
    action_code TEXT NOT NULL CHECK (action_code ~ '^[a-z_]{1,48}$'),
    source_code TEXT NOT NULL UNIQUE REFERENCES economy_v2_xp_sources(source_code) ON DELETE RESTRICT,
    display_name TEXT NOT NULL,
    PRIMARY KEY (career_code,action_code)
);

INSERT INTO economy_v2_xp_sources(source_code,reward_xp,cooldown_ms,career_code,active) VALUES
 ('career_ballet_practice',16,3600000,'ballet',true),
 ('career_volleyball_practice',16,3600000,'volleyball',true),
 ('career_cheer_practice',16,3600000,'cheer',true),
 ('activity_fish',8,300000,NULL,true),
 ('activity_mine',8,300000,NULL,true),
 ('activity_chop',8,300000,NULL,true)
ON CONFLICT (source_code) DO NOTHING;

INSERT INTO economy_v2_career_actions(career_code,action_code,source_code,display_name) VALUES
 ('ballet','practice','career_ballet_practice','Ballet practice'),
 ('volleyball','practice','career_volleyball_practice','Volleyball training'),
 ('cheer','practice','career_cheer_practice','Cheer practice')
ON CONFLICT (career_code,action_code) DO NOTHING;

-- Starter consumables are configured data, applied only by the gated Elixir activity path.
INSERT INTO economy_v2_consumable_effects(item_id,effect_code,duration_ms,active) VALUES
 ('practice_water','hydration_boost',1800000,true),
 ('practice_energy_bar','focus_boost',1800000,true)
ON CONFLICT (item_id) DO NOTHING;
INSERT INTO economy_v2_activity_effect_rules(effect_code,activity,credit_bonus_percent,active)
SELECT effect_code,activity,bonus,true
FROM (VALUES
 ('hydration_boost','fish',10),('hydration_boost','mine',10),('hydration_boost','chop',10),
 ('focus_boost','fish',15),('focus_boost','mine',15),('focus_boost','chop',15)
) AS configured(effect_code,activity,bonus)
ON CONFLICT (effect_code,activity) DO NOTHING;

-- Cosmetic selection is separate from clothing loadout and from consumable effects.
CREATE TABLE economy_v2_cosmetic_selections (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    slot TEXT NOT NULL CHECK (slot ~ '^[a-z_]{1,32}$'),
    item_id TEXT NOT NULL REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT,
    selected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,slot)
);
CREATE INDEX economy_v2_cosmetic_item_idx ON economy_v2_cosmetic_selections(item_id);

-- Meaningful catalog unlocks are evaluated centrally by the shop and profile.
UPDATE economy_v2_catalog_items SET level_requirement=3
WHERE item_id IN ('leopard_wrap_top','silver_zip_hoodie','rose_ribbed_dress','pearl_star_earrings',
                  'glitter_nail_palette','halloween_velvet_capelet','spring_blossom_bag');
UPDATE economy_v2_catalog_items SET level_requirement=5
WHERE item_id IN ('midnight_party_dress');
UPDATE economy_v2_catalog_items SET career_requirement='ballet',career_level_requirement=2
WHERE item_id IN ('ballet_practice_flats','ballet_lace_leotard');
UPDATE economy_v2_catalog_items SET career_requirement='volleyball',career_level_requirement=2
WHERE item_id IN ('volleyball_training_shorts','court_kneepads');
UPDATE economy_v2_catalog_items SET career_requirement='cheer',career_level_requirement=2
WHERE item_id IN ('cheer_pleated_skirt','captain_cheer_bow');
