package com.icloud.kevinmendoza.oreveins;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RegenerationExclusionsTest {
 @Test void negativeChunkEdgesAndWorldIdentityAreExact() {
  UUID id=UUID.randomUUID();
  var policy=new RegenerationExclusions(List.of(new RegenerationExclusions.Area("world",-16,-1,0,0)));
  assertTrue(policy.blocks("WORLD",id,-1,0)); assertFalse(policy.blocks("world",id,0,0));
  assertFalse(policy.blocks("other",id,-1,0)); assertFalse(policy.blocks("world",id,-1,-1));
  var byId=new RegenerationExclusions(List.of(new RegenerationExclusions.Area(id.toString(),0,0,0,0)));
  assertTrue(byId.blocks("renamed",id,0,0));
  assertFalse(byId.blocks("renamed",id,Integer.MAX_VALUE,0));
 }
 @Test void invalidPoliciesAreRejectedBeforeReload() {
  var config=new YamlConfiguration(); config.set("settings.excluded-areas", "bad");
  assertThrows(IllegalArgumentException.class,()->RegenerationExclusions.load(config));
  config.set("settings.excluded-areas",List.of(Map.of("world","world","min-x",1,"max-x",0,"min-z",0,"max-z",0)));
  assertThrows(IllegalArgumentException.class,()->RegenerationExclusions.load(config));
  config.set("settings.excluded-areas",List.of(Map.of("world","world","min-x",0.5,"max-x",10,"min-z",0,"max-z",0)));
  assertThrows(IllegalArgumentException.class,()->RegenerationExclusions.load(config));
 }
}
