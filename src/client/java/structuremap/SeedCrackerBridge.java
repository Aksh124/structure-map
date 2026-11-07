package structuremap;

import java.util.function.LongConsumer;

/**
 * Seed Cracker calls every "seedcracker:seed_found" entrypoint when it finds a seed. Using a
 * plain LongConsumer means neither mod needs the other to compile or run.
 */
public final class SeedCrackerBridge implements LongConsumer {
	@Override
	public void accept(long seed) {
		StructureMapClient.setSeed(seed, "Seed Cracker", true);
	}
}
