package com.icloud.kevinmendoza.oreveins;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import com.icloud.kevinmendoza.oreveins.GenerationSimulation.Position;

class SimulationConnectivityTest {
    @Test void faceNeighborsConnectButDiagonalsAndOtherMaterialsDoNot() {
        var sizes=GenerationSimulation.connectedSizes(Map.of(
                new Position(0,0,0),Material.IRON_ORE,
                new Position(1,0,0),Material.IRON_ORE,
                new Position(1,1,0),Material.IRON_ORE,
                new Position(2,2,0),Material.IRON_ORE,
                new Position(0,1,0),Material.GOLD_ORE));
        assertEquals(List.of(1,3),sizes.get(Material.IRON_ORE).stream().sorted().toList());
        assertEquals(List.of(1),sizes.get(Material.GOLD_ORE));
        var iron=GenerationSimulation.summarizeSizes(sizes).stream().filter(s->s.material()==Material.IRON_ORE).findFirst().orElseThrow();
        assertEquals(2,iron.components()); assertEquals(2.0,iron.mean()); assertEquals(2.0,iron.median());
    }
    @Test void emptyFixtureHasNoInventedVeinSizes() {
        assertTrue(GenerationSimulation.summarizeSizes(GenerationSimulation.connectedSizes(Map.of())).isEmpty());
    }
}
