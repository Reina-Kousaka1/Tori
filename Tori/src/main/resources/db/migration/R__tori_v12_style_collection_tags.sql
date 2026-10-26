-- Add a stable marker for the V12 style collection without replacing existing catalogue tags.
UPDATE economy_v2_catalog_items
SET tags = array_append(tags, 'v12_style_drop')
WHERE item_id = ANY(ARRAY[
  'leopard_ribbon_cami', 'leopard_tulip_skirt', 'leopard_lace_cardigan',
  'leopard_faux_fur_collar', 'leopard_heart_shoulder_bag', 'leopard_satin_bow',
  'leopard_ribbon_flats', 'leopard_charm_bracelet', 'leopard_heart_hoops',
  'ink_polka_picnic_dress', 'rose_polka_garden_dress', 'navy_polka_collar_dress',
  'cherry_dot_bow_dress', 'daisy_dot_party_dress', 'polka_puff_skirt',
  'polka_ruffle_scrunchie', 'polka_bow_clip', 'polka_pocket_bag',
  'polka_button_maryjanes', 'cloud_parade_dress', 'rainbow_ribbon_pinafore',
  'starlit_pony_pullover', 'friendship_cloud_cardigan', 'pastel_star_satchel',
  'cloudberry_snap_clips', 'rainbow_friendship_charms', 'moonbeam_heart_flats',
  'everyday_white_fitted_tee', 'everyday_grey_fitted_tee', 'navy_ribbon_neck_top',
  'blue_offshoulder_knit', 'bluebell_day_dress', 'cream_button_day_dress',
  'navy_schoolday_skirt', 'heart_pocket_crossbody', 'golden_layered_hearts',
  'violet_stone_pendant', 'pink_white_training_top', 'rose_warmup_zip',
  'ribbon_track_tank', 'blush_court_shorts', 'rally_pink_skort',
  'practice_day_bow', 'court_side_scrunchie', 'blush_training_sneakers',
  'little_heart_locket', 'three_strand_moon_necklace', 'sea_glass_drop_earrings',
  'lavender_pearl_studs', 'rose_gloss_nail_style', 'starry_lilac_nail_style'
]::text[])
  AND NOT ('v12_style_drop' = ANY(tags));
