# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-09-28
### Added
- ensureBiomeForTheWholeZone can now accept a biome resource id, which allow to enforce a different biome than the biome 
it was placed at.
- new common-config parameters: zoneOnlyBiomes, zoneOnlyMobs that makes configured biomes/mobs to not be placed/summoned, 
naturally by the game. Meaning that custom logic that changes biome or summoning the mobs by spawners/spawn eggs/commands  
left unaffected by the mod and still work the same.
- new zone-level option "mobs": allows to change mob spawning weights inside zones.
- TerraBlender compatibility (tested with The Biomes We Have Gone mod)

### Fixed
- Fixed a bug that surface block was not placed correctly for zones configured with ensureBiomeForTheWholeZone 
- and shouldFlattenTerrain