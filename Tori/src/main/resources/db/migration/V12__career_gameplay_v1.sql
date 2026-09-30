-- Career actions reuse existing account/career XP, request idempotency, wallet and ledger tables.
-- This migration only adds action configuration; existing balances and progress are untouched.
ALTER TABLE economy_v2_career_actions
    ADD COLUMN reward_credits BIGINT NOT NULL DEFAULT 0 CHECK (reward_credits >= 0),
    ADD COLUMN required_career_level INTEGER NOT NULL DEFAULT 1 CHECK (required_career_level >= 1),
    ADD COLUMN required_item_id TEXT REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT;

-- Keep the original practice XP sources (and any historical XP events) while naming
-- each starter action after its place in the career loop.
UPDATE economy_v2_career_actions
SET action_code = CASE career_code
        WHEN 'ballet' THEN 'class'
        WHEN 'volleyball' THEN 'court_practice'
        WHEN 'cheer' THEN 'squad_practice'
    END,
    display_name = CASE career_code
        WHEN 'ballet' THEN 'Ballet class'
        WHEN 'volleyball' THEN 'Court practice'
        WHEN 'cheer' THEN 'Cheer practice'
    END
WHERE action_code = 'practice'
  AND career_code IN ('ballet', 'volleyball', 'cheer');

INSERT INTO economy_v2_xp_sources(source_code,reward_xp,cooldown_ms,career_code,active) VALUES
    ('career_ballet_barre',20,1800000,'ballet',true),
    ('career_ballet_rehearsal',24,3600000,'ballet',true),
    ('career_ballet_performance',30,14400000,'ballet',true),
    ('career_volleyball_drills',20,1800000,'volleyball',true),
    ('career_volleyball_scrimmage',24,3600000,'volleyball',true),
    ('career_volleyball_match',30,14400000,'volleyball',true),
    ('career_cheer_tumbling',20,1800000,'cheer',true),
    ('career_cheer_routine',24,3600000,'cheer',true),
    ('career_cheer_competition',30,14400000,'cheer',true)
ON CONFLICT (source_code) DO UPDATE
SET reward_xp=EXCLUDED.reward_xp,
    cooldown_ms=EXCLUDED.cooldown_ms,
    career_code=EXCLUDED.career_code,
    active=EXCLUDED.active;

INSERT INTO economy_v2_career_actions
    (career_code,action_code,source_code,display_name,reward_credits,required_career_level,required_item_id)
VALUES
    ('ballet','class','career_ballet_practice','Ballet class',10,1,NULL),
    ('ballet','barre','career_ballet_barre','Barre work',14,2,NULL),
    ('ballet','rehearsal','career_ballet_rehearsal','Rehearsal',18,3,'rehearsal_wrap_skirt'),
    ('ballet','performance','career_ballet_performance','Performance',22,4,'practice_pointe_flats'),
    ('volleyball','court_practice','career_volleyball_practice','Court practice',10,1,NULL),
    ('volleyball','drills','career_volleyball_drills','Skill drills',14,2,NULL),
    ('volleyball','scrimmage','career_volleyball_scrimmage','Scrimmage',18,3,'navy_warm_gold_court_jersey'),
    ('volleyball','match','career_volleyball_match','Match',22,4,'rally_point_shoes'),
    ('cheer','squad_practice','career_cheer_practice','Cheer practice',10,1,NULL),
    ('cheer','tumbling_stunts','career_cheer_tumbling','Tumbling and stunts',14,2,NULL),
    ('cheer','routine','career_cheer_routine','Routine work',18,3,'navy_lavender_cheer_shell'),
    ('cheer','competition','career_cheer_competition','Competition',22,4,'competition_day_ribbon_set')
ON CONFLICT (career_code,action_code) DO UPDATE
SET source_code=EXCLUDED.source_code,
    display_name=EXCLUDED.display_name,
    reward_credits=EXCLUDED.reward_credits,
    required_career_level=EXCLUDED.required_career_level,
    required_item_id=EXCLUDED.required_item_id;
