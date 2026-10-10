package com.github.skjolber.packing.service.service.blockbeam;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.service.rule.PlacementGeometry;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

/**
 * Entry point for bounded search over homogeneous blocks and a supported
 * maximal-space cover. Mutable search state lives in {@link BlockBeamSearchSession}.
 */
public final class BlockBeamSearchPackager {

	private static final int DEFAULT_BEAM_WIDTH = 64;
	private static final int DEFAULT_BRANCHING = 48;

	private final SearchProfile searchProfile;
	private final BlockBeamMoveGenerator moveGenerator;
	private final BlockBeamSelector selector;

	public BlockBeamSearchPackager() {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING, PlacementSupport.DEFAULT_POLICY);
	}

	public BlockBeamSearchPackager(int beamWidth, int branching) {
		this(beamWidth, branching, PlacementSupport.DEFAULT_POLICY);
	}

	public BlockBeamSearchPackager(PlacementSupport.Policy supportPolicy) {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING, supportPolicy);
	}

	public BlockBeamSearchPackager(int beamWidth, int branching, PlacementSupport.Policy supportPolicy) {
		this(beamWidth, branching, supportPolicy, SearchProfile.BALANCED);
	}

	public BlockBeamSearchPackager(int beamWidth, int branching, PlacementSupport.Policy supportPolicy,
			SearchProfile searchProfile) {
		this.searchProfile = searchProfile;
		this.moveGenerator = new BlockBeamMoveGenerator(branching, supportPolicy, searchProfile);
		this.selector = new BlockBeamSelector(beamWidth, searchProfile);
	}

	public PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis) {
		return pack(sourceContainer, items, deadlineMillis, "");
	}

	public PackagerResult pack(Container sourceContainer, List<BoxItem> items,
			long deadlineMillis, String logContext) {
		SearchSession session = newSession(sourceContainer, items, logContext);
		return session == null ? null : session.advance(deadlineMillis).result();
	}

	public SearchProgress probe(Container sourceContainer, List<BoxItem> items,
			int maximumExpandedStates, String logContext) {
		if (maximumExpandedStates <= 0) {
			throw new IllegalArgumentException("maximumExpandedStates must be positive");
		}
		SearchSession session = newSession(sourceContainer, items, logContext);
		return session == null ? SearchProgress.empty(items) : session.advanceStates(maximumExpandedStates);
	}

	public PackagerResult pack(Container sourceContainer, List<BoxItem> items,
			List<Placement> fixedPlacements, long deadlineMillis, String logContext) {
		SearchSession session = newSession(sourceContainer, items, fixedPlacements, logContext);
		return session == null ? null : session.advance(deadlineMillis).result();
	}

	SearchSession newSession(Container sourceContainer, List<BoxItem> items, String logContext) {
		return newSession(sourceContainer, items, List.of(), logContext);
	}

	SearchSession newSession(Container sourceContainer, List<BoxItem> items,
			List<Placement> fixedPlacements, String logContext) {
		if (items.isEmpty()) return null;
		Container container = sourceContainer.clone();
		List<Placement> fixed = List.copyOf(fixedPlacements);
		validateFixedPlacements(container, fixed);
		List<Block> catalog = BlockBeamBlockCatalog.generate(container, items);
		if (catalog.isEmpty()) return null;
		return new SearchSession(new BlockBeamSearchSession(container, List.copyOf(items), catalog, fixed,
				logContext, searchProfile, moveGenerator, selector));
	}

	final class SearchSession {
		private final BlockBeamSearchSession delegate;

		private SearchSession(BlockBeamSearchSession delegate) {
			this.delegate = delegate;
		}

		SearchProgress advance(long deadlineMillis) {
			return delegate.advance(deadlineMillis);
		}

		SearchProgress advanceStates(int maximumAdditionalStates) {
			return delegate.advanceStates(maximumAdditionalStates);
		}

		SearchProgress progress() {
			return delegate.progress();
		}

		PartialSolution bestPartial() {
			return delegate.bestPartial();
		}
	}

	public record SearchProgress(PackagerResult result, boolean exhausted, int packedUnits,
			int totalUnits, long packedVolume, long totalVolume, int eliteStates,
			int expandedStates, int generatedStates, long activeMillis) {
		public boolean complete() {
			return result != null && result.isSuccess();
		}

		public double completionRatio() {
			return totalUnits == 0 ? 0.0 : packedUnits / (double) totalUnits;
		}

		public double volumeCompletionRatio() {
			return totalVolume == 0L ? 0.0 : packedVolume / (double) totalVolume;
		}

		private static SearchProgress empty(List<BoxItem> items) {
			int units = items.stream().mapToInt(BoxItem::getCount).sum();
			long volume = items.stream()
					.mapToLong(item -> item.getBox().getVolume() * (long) item.getCount()).sum();
			return new SearchProgress(null, true, 0, units, 0L, volume, 0, 0, 0, 0L);
		}
	}

	record PartialSolution(Container container, List<BoxItem> remainingItems,
			long loadedVolume, long targetVolume, int packedUnits, int totalUnits) {
		double volumeCompletionRatio() {
			return targetVolume == 0L ? 0.0 : loadedVolume / (double) targetVolume;
		}
	}

	private static void validateFixedPlacements(Container container, List<Placement> placements) {
		PlacementGeometry.Validation validation = PlacementGeometry.validate(container, placements);
		if (!validation.valid()) {
			throw new IllegalArgumentException("Invalid fixed placements: " + validation.message());
		}
	}

	List<State> selectBeam(List<State> candidates, Container container) {
		return selector.select(candidates, container);
	}

	static Feasibility feasibility(int[] remaining, List<Space> spaces, List<BoxItem> items) {
		return BlockBeamStateEvaluator.feasibility(remaining, spaces, items);
	}

	record Block(int itemIndex, Box box, BoxStackValue orientation,
			int nx, int ny, int nz, int units, int dx, int dy, int dz, int weight, long volume) {
	}

	record BlockPlacement(Block block, int x, int y, int z) {
		void expandInto(List<Placement> target) {
			for (int ix = 0; ix < block.nx(); ix++) {
				for (int iy = 0; iy < block.ny(); iy++) {
					for (int iz = 0; iz < block.nz(); iz++) {
						target.add(new Placement(block.orientation(), -1,
								x + ix * block.orientation().getDx(),
								y + iy * block.orientation().getDy(),
								z + iz * block.orientation().getDz()));
					}
				}
			}
		}
	}

	record Move(int spaceIndex, Block block, boolean yFirst, double rank) {
		String key() {
			return spaceIndex + ":" + block.itemIndex() + ":" + block.orientation().getIndex()
					+ ":" + block.nx() + ":" + block.ny() + ":" + block.nz();
		}
	}

	public enum SearchProfile {
		BALANCED,
		VOLUME_FIRST,
		SPACE_EFFICIENCY,
		COMPACT_FRONT;

		String propertyValue() {
			return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
		}
	}

	record Feasibility(int strandedTypes, int scarceTypes,
			int minimumFitSpaces, int minimumClearance) {
	}

	record State(int[] remaining, List<Space> spaces, List<BlockPlacement> placements,
			long packedVolume, int weight, int maxX, int maxZ, Feasibility feasibility) {
		boolean complete() {
			for (int value : remaining) if (value != 0) return false;
			return true;
		}

		boolean intersects(int x, int y, int z, Block block) {
			int endX = x + block.dx();
			int endY = y + block.dy();
			int endZ = z + block.dz();
			for (BlockPlacement placement : placements) {
				Block placed = placement.block();
				if (x < placement.x() + placed.dx() && endX > placement.x()
						&& y < placement.y() + placed.dy() && endY > placement.y()
						&& z < placement.z() + placed.dz() && endZ > placement.z()) return true;
			}
			return false;
		}

		double score(Container container) {
			double fill = packedVolume / (double) container.getMaxLoadVolume();
			double length = maxX / (double) container.getLoadDx();
			double height = maxZ / (double) container.getLoadDz();
			long largestSpace = spaces.stream().mapToLong(Space::volume).max().orElse(0L);
			return fill * 1_000_000.0 + largestSpace / (double) container.getMaxLoadVolume() * 5_000.0
					+ feasibility.minimumFitSpaces() * 2_000.0
					+ feasibility.minimumClearance() * 5.0
					- feasibility.scarceTypes() * 500.0
					- feasibility.strandedTypes() * 10_000_000.0
					- spaces.size() * 15.0 - length * 100.0 - height * 50.0;
		}

		String key() {
			StringBuilder builder = new StringBuilder(Arrays.toString(remaining)).append('|');
			for (Space space : spaces) builder.append(space.key()).append(';');
			return builder.toString();
		}
	}

	record Space(int x, int y, int z, int dx, int dy, int dz) {
		static final Comparator<Space> ORDER = Comparator.comparingInt(Space::z)
				.thenComparingInt(Space::y).thenComparingInt(Space::x)
				.thenComparingInt(Space::dz).thenComparingInt(Space::dy).thenComparingInt(Space::dx);

		boolean fits(Block block) {
			return block.dx() <= dx && block.dy() <= dy && block.dz() <= dz;
		}

		long volume() {
			return (long) dx * dy * dz;
		}

		int endX() { return x + dx; }
		int endY() { return y + dy; }
		int endZ() { return z + dz; }

		boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			return x < maxX && endX() > minX && y < maxY && endY() > minY
					&& z < maxZ && endZ() > minZ;
		}

		boolean contains(Space other) {
			return z == other.z && x <= other.x && y <= other.y
					&& endX() >= other.endX() && endY() >= other.endY() && endZ() >= other.endZ();
		}

		String key() {
			return x + "," + y + "," + z + "," + dx + "," + dy + "," + dz;
		}
	}
}
