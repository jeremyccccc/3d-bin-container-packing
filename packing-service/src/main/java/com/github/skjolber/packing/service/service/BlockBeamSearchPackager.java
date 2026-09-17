package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/**
 * Bounded search over homogeneous blocks and a maximal-space cover whose bases
 * are fully supported.
 * It is intentionally independent of the core packagers so it can be evaluated
 * as an additional candidate without changing their established behaviour.
 */
final class BlockBeamSearchPackager {

	private static final int DEFAULT_BEAM_WIDTH = 64;
	private static final int DEFAULT_BRANCHING = 48;
	private static final int MAX_VERTICAL_UNITS = 5;
	private static final int TRACKED_FIT_SPACES = 4;

	private final int beamWidth;
	private final int branching;

	BlockBeamSearchPackager() {
		this(DEFAULT_BEAM_WIDTH, DEFAULT_BRANCHING);
	}

	BlockBeamSearchPackager(int beamWidth, int branching) {
		this.beamWidth = beamWidth;
		this.branching = branching;
	}

	PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis) {
		return pack(sourceContainer, items, deadlineMillis, "");
	}

	PackagerResult pack(Container sourceContainer, List<BoxItem> items, long deadlineMillis, String logContext) {
		long started = System.currentTimeMillis();
		if (items.isEmpty()) return null;
		Container container = sourceContainer.clone();
		List<Block> catalog = generateBlocks(container, items);
		if (catalog.isEmpty()) return null;

		int[] initialRemaining = items.stream().mapToInt(BoxItem::getCount).toArray();
		List<Space> initialSpaces = List.of(
				new Space(0, 0, 0, container.getLoadDx(), container.getLoadDy(), container.getLoadDz()));
		State initial = new State(initialRemaining, initialSpaces, List.of(), 0L, 0, 0, 0,
				feasibility(initialRemaining, initialSpaces, items));
		List<State> beam = List.of(initial);
		Set<String> visited = new HashSet<>();
		visited.add(initial.key());
		int expandedStates = 0;
		int generatedStates = 0;

		while (!beam.isEmpty() && !expired(deadlineMillis)) {
			List<State> next = new ArrayList<>();
			for (State state : beam) {
				if (state.complete()) {
					return result(container, state, started, expandedStates, generatedStates, logContext);
				}
				expandedStates++;
				for (Move move : moves(state, catalog, container)) {
					State child = apply(state, move, container, items);
					generatedStates++;
					// Once no current maximal space can accommodate a remaining item,
					// later placements can only shrink those spaces. Keep that dead end
					// out of the beam before it displaces a viable alternative.
					if (child.feasibility().strandedTypes() == 0 && visited.add(child.key())) next.add(child);
				}
			}
			if (next.isEmpty()) break;
			next = selectBeam(next, container);
			for (State state : next) {
				if (state.complete()) {
					return result(container, state, started, expandedStates, generatedStates, logContext);
				}
			}
			beam = next;
		}
		System.out.println("packing-service BLOCK-BEAM" + logContext + " success=false elapsedMs="
				+ (System.currentTimeMillis() - started) + " expanded=" + expandedStates
				+ " generated=" + generatedStates + " blockTypes=" + catalog.size());
		return null;
	}

	private PackagerResult result(Container container, State state, long started,
			int expandedStates, int generatedStates, String logContext) {
		List<Placement> placements = new ArrayList<>();
		for (BlockPlacement placement : state.placements()) placement.expandInto(placements);
		container.getStack().addAll(placements);
		long elapsed = System.currentTimeMillis() - started;
		System.out.println("packing-service BLOCK-BEAM" + logContext + " success=true elapsedMs=" + elapsed
				+ " expanded=" + expandedStates + " generated=" + generatedStates
				+ " blocks=" + state.placements().size() + " placements=" + placements.size());
		return new PackagerResult(List.of(container), elapsed, false);
	}

	private List<Move> moves(State state, List<Block> catalog, Container container) {
		PriorityQueue<Move> best = new PriorityQueue<>(Comparator.comparingDouble(Move::rank));
		Map<Integer, Move> bestByItem = new HashMap<>();
		for (int spaceIndex = 0; spaceIndex < state.spaces().size(); spaceIndex++) {
			Space space = state.spaces().get(spaceIndex);
			for (Block block : catalog) {
				if (state.remaining()[block.itemIndex()] < block.units() || !space.fits(block)
						|| state.intersects(space.x(), space.y(), space.z(), block)) continue;
				if ((long) state.weight() + block.weight() > container.getMaxLoadWeight()) continue;
				double rank = moveRank(space, block, container);
				Move move = new Move(spaceIndex, block, false, rank);
				add(best, move);
				bestByItem.merge(block.itemIndex(), move,
						(a, b) -> a.rank() >= b.rank() ? a : b);
			}
		}
		Map<String, Move> unique = new LinkedHashMap<>();
		for (Move move : best) unique.put(move.key(), move);
		for (Move move : bestByItem.values()) unique.put(move.key(), move);
		List<Move> result = new ArrayList<>(unique.values());
		result.sort(Comparator.comparingDouble(Move::rank).reversed());
		return result;
	}

	private void add(PriorityQueue<Move> best, Move move) {
		if (best.size() < branching) {
			best.add(move);
		} else if (move.rank() > best.peek().rank()) {
			best.poll();
			best.add(move);
		}
	}

	private static double moveRank(Space space, Block block, Container container) {
		double fill = block.volume() / (double) space.volume();
		double footprint = (long) block.dx() * block.dy() / (double) ((long) space.dx() * space.dy());
		double wall = (space.x() == 0 ? 1 : 0) + (space.y() == 0 ? 1 : 0)
				+ (space.x() + block.dx() == container.getLoadDx() ? 1 : 0)
				+ (space.y() + block.dy() == container.getLoadDy() ? 1 : 0);
		return block.volume() / (double) container.getMaxLoadVolume() * 10_000_000.0
				+ block.units() * 100.0
				+ fill * 100_000.0 + footprint * 20_000.0 + wall * 1_000.0
				- space.z() * 0.01;
	}

	private State apply(State state, Move move, Container container, List<BoxItem> items) {
		Space used = state.spaces().get(move.spaceIndex());
		Block block = move.block();
		int[] remaining = state.remaining().clone();
		remaining[block.itemIndex()] -= block.units();

		List<BlockPlacement> placements = new ArrayList<>(state.placements());
		placements.add(new BlockPlacement(block, used.x(), used.y(), used.z()));
		List<Space> spaces = updateSpaces(state.spaces(), used.x(), used.y(), used.z(), block, container);
		return new State(remaining, spaces, placements,
				state.packedVolume() + block.volume(), state.weight() + block.weight(),
				Math.max(state.maxX(), used.x() + block.dx()),
				Math.max(state.maxZ(), used.z() + block.dz()), feasibility(remaining, spaces, items));
	}

	List<State> selectBeam(List<State> candidates, Container container) {
		if (candidates.size() <= beamWidth) {
			candidates.sort(stateComparator(container));
			return candidates;
		}

		Comparator<State> overall = stateComparator(container);
		Comparator<State> criticalSpace = Comparator
				.comparingInt((State state) -> state.feasibility().strandedTypes())
				.thenComparing(Comparator.comparingInt(
						(State state) -> state.feasibility().minimumFitSpaces()).reversed())
				.thenComparingInt(state -> state.feasibility().scarceTypes())
				.thenComparing(Comparator.comparingInt(
						(State state) -> state.feasibility().minimumClearance()).reversed())
				.thenComparing(overall);
		Comparator<State> openSpace = Comparator
				.comparingLong((State state) -> largestSpace(state.spaces())).reversed()
				.thenComparingInt(state -> state.spaces().size())
				.thenComparing(overall);

		LinkedHashSet<State> selected = new LinkedHashSet<>(beamWidth * 2);
		addBest(selected, candidates, overall,
				availableQuota(selected, Math.max(1, beamWidth / 2)));
		addBest(selected, candidates, criticalSpace,
				availableQuota(selected, Math.max(1, beamWidth / 4)));
		addBest(selected, candidates, openSpace,
				availableQuota(selected, Math.max(1, beamWidth / 8)));
		addShapeDiversity(selected, candidates, overall, container,
				availableQuota(selected, Math.max(1, beamWidth / 8)));
		addBest(selected, candidates, overall, beamWidth - selected.size());
		return new ArrayList<>(selected);
	}

	private int availableQuota(Set<State> selected, int requested) {
		return Math.max(0, Math.min(requested, beamWidth - selected.size()));
	}

	private static void addBest(Set<State> selected, List<State> candidates,
			Comparator<State> comparator, int count) {
		if (count <= 0) return;
		List<State> sorted = new ArrayList<>(candidates);
		sorted.sort(comparator);
		int added = 0;
		for (State state : sorted) {
			if (selected.add(state) && ++added >= count) return;
		}
	}

	private static void addShapeDiversity(Set<State> selected, List<State> candidates,
			Comparator<State> comparator, Container container, int count) {
		if (count <= 0) return;
		List<State> sorted = new ArrayList<>(candidates);
		sorted.sort(comparator);
		Set<String> buckets = new HashSet<>();
		int added = 0;
		for (State state : sorted) {
			int lengthBucket = 4 * state.maxX() / Math.max(1, container.getLoadDx());
			int heightBucket = 4 * state.maxZ() / Math.max(1, container.getLoadDz());
			int spaceBucket = Math.min(7, state.spaces().size() / 4);
			String bucket = lengthBucket + ":" + heightBucket + ":" + spaceBucket;
			if (selected.contains(state) || !buckets.add(bucket)) continue;
			selected.add(state);
			if (++added >= count) return;
		}
	}

	private static long largestSpace(List<Space> spaces) {
		long largest = 0L;
		for (Space space : spaces) largest = Math.max(largest, space.volume());
		return largest;
	}

	static Feasibility feasibility(int[] remaining, List<Space> spaces, List<BoxItem> items) {
		int strandedTypes = 0;
		int scarceTypes = 0;
		int minimumFitSpaces = Integer.MAX_VALUE;
		int minimumClearance = Integer.MAX_VALUE;
		for (int itemIndex = 0; itemIndex < remaining.length; itemIndex++) {
			if (remaining[itemIndex] == 0) continue;
			Box box = items.get(itemIndex).getBox();
			int fitSpaces = 0;
			int bestClearance = -1;
			for (Space space : spaces) {
				int clearance = clearance(box, space);
				if (clearance < 0) continue;
				fitSpaces++;
				bestClearance = Math.max(bestClearance, clearance);
				if (fitSpaces >= TRACKED_FIT_SPACES) break;
			}
			if (fitSpaces == 0) {
				strandedTypes++;
				minimumFitSpaces = 0;
				minimumClearance = -1;
				continue;
			}
			minimumFitSpaces = Math.min(minimumFitSpaces, fitSpaces);
			minimumClearance = Math.min(minimumClearance, bestClearance);
			if (fitSpaces <= 2) scarceTypes++;
		}
		if (minimumFitSpaces == Integer.MAX_VALUE) minimumFitSpaces = TRACKED_FIT_SPACES;
		if (minimumClearance == Integer.MAX_VALUE) minimumClearance = 0;
		return new Feasibility(strandedTypes, scarceTypes, minimumFitSpaces, minimumClearance);
	}

	private static int clearance(Box box, Space space) {
		int best = -1;
		for (BoxStackValue orientation : box.getStackValues()) {
			if (orientation.getDx() > space.dx() || orientation.getDy() > space.dy()
					|| orientation.getDz() > space.dz()) continue;
			best = Math.max(best, Math.min(space.dx() - orientation.getDx(),
					Math.min(space.dy() - orientation.getDy(), space.dz() - orientation.getDz())));
		}
		return best;
	}

	private static List<Space> updateSpaces(List<Space> source, int x, int y, int z,
			Block block, Container container) {
		int endX = x + block.dx();
		int endY = y + block.dy();
		int endZ = z + block.dz();
		List<Space> spaces = new ArrayList<>();
		for (Space space : source) {
			if (!space.intersects(x, y, z, endX, endY, endZ)) {
				spaces.add(space);
				continue;
			}
			int intersectionMinX = Math.max(space.x(), x);
			int intersectionMaxX = Math.min(space.endX(), endX);
			int intersectionMinY = Math.max(space.y(), y);
			int intersectionMaxY = Math.min(space.endY(), endY);
			int intersectionMinZ = Math.max(space.z(), z);
			addSpace(spaces, space.x(), space.y(), space.z(),
					intersectionMinX - space.x(), space.dy(), space.dz());
			addSpace(spaces, intersectionMaxX, space.y(), space.z(),
					space.endX() - intersectionMaxX, space.dy(), space.dz());
			addSpace(spaces, space.x(), space.y(), space.z(),
					space.dx(), intersectionMinY - space.y(), space.dz());
			addSpace(spaces, space.x(), intersectionMaxY, space.z(),
					space.dx(), space.endY() - intersectionMaxY, space.dz());
			addSpace(spaces, space.x(), space.y(), space.z(),
					space.dx(), space.dy(), intersectionMinZ - space.z());
		}
		addSpace(spaces, x, y, endZ, block.dx(), block.dy(), container.getLoadDz() - endZ);
		return normalize(spaces);
	}

	private static void addSpace(List<Space> spaces, int x, int y, int z, int dx, int dy, int dz) {
		if (dx > 0 && dy > 0 && dz > 0) spaces.add(new Space(x, y, z, dx, dy, dz));
	}

	private static List<Space> normalize(List<Space> input) {
		List<Space> spaces = new ArrayList<>(new LinkedHashMap<>(input.stream()
				.collect(java.util.stream.Collectors.toMap(Space::key, s -> s, (a, b) -> a,
						LinkedHashMap::new))).values());
		for (int i = spaces.size() - 1; i >= 0; i--) {
			Space candidate = spaces.get(i);
			for (int j = 0; j < spaces.size(); j++) {
				if (i != j && spaces.get(j).contains(candidate)) {
					spaces.remove(i);
					break;
				}
			}
		}
		spaces.sort(Space.ORDER);
		return spaces;
	}

	private static List<Block> generateBlocks(Container container, List<BoxItem> items) {
		Map<String, Block> unique = new LinkedHashMap<>();
		for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
			BoxItem item = items.get(itemIndex);
			for (BoxStackValue orientation : item.getBox().getStackValues()) {
				int maxX = Math.min(item.getCount(), container.getLoadDx() / orientation.getDx());
				int maxY = Math.min(item.getCount(), container.getLoadDy() / orientation.getDy());
				int maxZ = Math.min(Math.min(item.getCount(), MAX_VERTICAL_UNITS),
						container.getLoadDz() / orientation.getDz());
				if (maxX == 0 || maxY == 0 || maxZ == 0) continue;
				for (int z = 1; z <= maxZ; z++) {
					for (int y = 1; y <= maxY; y++) {
						int x = Math.min(maxX, item.getCount() / (y * z));
						addBlock(unique, itemIndex, item.getBox(), orientation, x, y, z);
						addBlock(unique, itemIndex, item.getBox(), orientation, 1, y, z);
					}
					for (int x = 1; x <= maxX; x++) {
						int y = Math.min(maxY, item.getCount() / (x * z));
						addBlock(unique, itemIndex, item.getBox(), orientation, x, y, z);
					}
				}
			}
		}
		List<Block> blocks = new ArrayList<>(unique.values());
		blocks.sort(Comparator.comparingInt(Block::units).reversed()
				.thenComparing(Comparator.comparingLong(Block::volume).reversed())
				.thenComparingInt(Block::dz));
		return blocks;
	}

	private static void addBlock(Map<String, Block> blocks, int itemIndex, Box box,
			BoxStackValue orientation, int nx, int ny, int nz) {
		if (nx < 1 || ny < 1 || nz < 1) return;
		int units = nx * ny * nz;
		String key = itemIndex + ":" + orientation.getIndex() + ":" + nx + ":" + ny + ":" + nz;
		blocks.putIfAbsent(key, new Block(itemIndex, box, orientation, nx, ny, nz,
				units, orientation.getDx() * nx, orientation.getDy() * ny,
				orientation.getDz() * nz, Math.multiplyExact(box.getWeight(), units),
				Math.multiplyExact(box.getVolume(), units)));
	}

	private static Comparator<State> stateComparator(Container container) {
		return Comparator.comparingDouble((State state) -> state.score(container)).reversed();
	}

	private static boolean expired(long deadlineMillis) {
		return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private record Block(int itemIndex, Box box, BoxStackValue orientation,
			int nx, int ny, int nz, int units, int dx, int dy, int dz, int weight, long volume) {
	}

	private record BlockPlacement(Block block, int x, int y, int z) {
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

	private record Move(int spaceIndex, Block block, boolean yFirst, double rank) {
		String key() {
			return spaceIndex + ":" + block.itemIndex() + ":" + block.orientation().getIndex()
					+ ":" + block.nx() + ":" + block.ny() + ":" + block.nz();
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
