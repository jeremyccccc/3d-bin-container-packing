package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Packager;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.comparator.LargestAreaBoxItemComparator;
import com.github.skjolber.packing.comparator.VolumeThenWeightBoxItemComparator;
import com.github.skjolber.packing.packer.AbstractPackagerResultBuilder;
import com.github.skjolber.packing.packer.laff.FastLargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.laff.LargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.plain.PlainPackager;
import com.github.skjolber.packing.packer.plain.PlainPlacementComparator;

@Component
class PackingEngine {
	private static final long WHOLE_ORDER_SEARCH_MILLIS = 180_000L;
	private static final long BETTER_SOLUTION_SEARCH_MILLIS = 15_000L;
	private static final long BLOCK_BEAM_SEARCH_MILLIS = 30_000L;

	private final WholeOrderAssignmentSolver assignmentSolver = new WholeOrderAssignmentSolver();
	private final PlacementSupport.Policy supportPolicy;
	private final boolean diagnosticsEnabled;
	private final boolean blockBeamOnly;

	PackingEngine() {
		this(PlacementSupport.DEFAULT_POLICY, false, false);
	}

	@Autowired
	PackingEngine(
			@Value("${packing.support.minimum-area-ratio:0.85}") double minimumAreaRatio,
			@Value("${packing.support.require-center:true}") boolean requireCenterSupport,
			@Value("${packing.support.maximum-overhang-mm:20}") int maximumOverhangMillimeters,
			@Value("${packing.support.maximum-overhang-ratio:0.05}") double maximumOverhangRatio,
			@Value("${packing.diagnostics.enabled:false}") boolean diagnosticsEnabled,
			@Value("${packing.block-beam-only:false}") boolean blockBeamOnly) {
		this(new PlacementSupport.Policy(minimumAreaRatio, requireCenterSupport,
				maximumOverhangMillimeters, maximumOverhangRatio), diagnosticsEnabled, blockBeamOnly);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy) {
		this(supportPolicy, false, false);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled) {
		this(supportPolicy, diagnosticsEnabled, false);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly) {
		this.supportPolicy = supportPolicy;
		this.diagnosticsEnabled = diagnosticsEnabled;
		this.blockBeamOnly = blockBeamOnly;
	}

	PackagerResult pack(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return null;
		}

		// A one-container request already satisfies the whole-order rule. For
		// multiple physical containers, assign complete house bills first instead
		// of spending time on a flat result which is likely to split them.
		if (totalContainerCount(plan.containerItems()) == 1) {
			return packFlat(plan, 0L);
		}
		return packWholeOrders(plan);
	}

	private PackagerResult packWholeOrders(PackingPlan plan) {
		long deadline = System.currentTimeMillis() + WHOLE_ORDER_SEARCH_MILLIS;
		List<ContainerItem> physicalContainers = expandContainerItems(plan.containerItems());
		List<WholeOrderAssignmentSolver.Candidate> candidates = assignmentSolver.candidates(plan);
		PackagerResult best = null;
		int attempted = 0;
		int successful = 0;
		long stopAt = deadline;
		for (WholeOrderAssignmentSolver.Candidate candidate : candidates) {
			if (System.currentTimeMillis() >= stopAt) {
				break;
			}
			attempted++;
			List<Container> packedContainers = new ArrayList<>();
			long duration = 0L;
			boolean success = true;
			for (int i = 0; i < physicalContainers.size(); i++) {
				List<com.github.skjolber.packing.api.BoxItem> items = candidate.itemsByContainer().get(i);
				if (items.isEmpty()) {
					continue;
				}
				PackingPlan containerPlan = new PackingPlan(
						plan.requestedContainers(),
						List.of(physicalContainers.get(i)),
						items,
						plan.cargoLines(),
						plan.warnings());
				PackagerResult packed = packFlat(containerPlan, deadline);
				if (packed == null || !packed.isSuccess() || packed.size() != 1) {
					success = false;
					break;
				}
				packedContainers.add(packed.get(0));
				duration += packed.getDuration();
			}
			if (success) {
				PackagerResult packed = new PackagerResult(packedContainers, duration, false);
				if (!splitsHouseBills(packed)) {
					successful++;
					System.out.println("packing-service whole-order success source=" + candidate.source()
							+ " attempt=" + attempted + " containerCount=" + packed.size());
					best = better(best, packed);
					if (successful == 1) {
						stopAt = Math.min(deadline, System.currentTimeMillis() + BETTER_SOLUTION_SEARCH_MILLIS);
					}
				}
			}
		}
		System.out.println("packing-service whole-order candidates=" + candidates.size()
				+ " attempted=" + attempted + " successful=" + successful + " success=" + (best != null));
		return best;
	}

	private static List<ContainerItem> expandContainerItems(List<ContainerItem> items) {
		List<ContainerItem> result = new ArrayList<>();
		for (ContainerItem item : items) {
			for (int i = 0; i < item.getCount(); i++) {
				result.add(new ContainerItem(item.getContainer(), 1));
			}
		}
		return result;
	}

	private static boolean splitsHouseBills(PackagerResult result) {
		Map<String, Integer> containersByHouseBill = new HashMap<>();
		for (int containerIndex = 0; containerIndex < result.getContainers().size(); containerIndex++) {
			Container container = result.getContainers().get(containerIndex);
			for (com.github.skjolber.packing.api.Placement placement : container.getStack().getPlacements()) {
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				if (houseBsId == null) {
					continue;
				}
				Integer previous = containersByHouseBill.putIfAbsent(houseBsId, containerIndex);
				if (previous != null && previous != containerIndex) {
					return true;
				}
			}
		}
		return false;
	}

	private PackagerResult packFlat(PackingPlan plan, long deadline) {
		PackagerResult best = null;
		best = better(best, packOrientation(plan, false, deadline));
		if (best != null && best.isSuccess() && best.size() == 1) {
			return best;
		}
		best = better(best, packOrientation(plan, true, deadline));
		return best;
	}

	private PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth, long deadline) {
		List<ContainerItem> containers = swapLengthWidth ? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult best = null;
		PackagerResult blockBeam = tryBlockBeamSearch(plan, containers,
				"BlockBeam" + (swapLengthWidth ? "-SWAPPED" : ""), deadline);
		if (blockBeam != null && blockBeam.isSuccess()) {
			return blockBeam;
		}
		if (blockBeamOnly) {
			return null;
		}
		if (hasBusinessRule(plan)) {
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			best = better(best, tryMacroPacks(plan, containers,
					"Plain-Rules-MACRO-Y", swapLengthWidth,
					hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
					hasDoorSideRule, deadline));
			best = better(best, tryPack(plan, containers, "Plain-Rules-LAYOUT" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new LayoutPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							supportPolicy,
							hasDoorSideRule,
							true))
					.build(), deadline));
			best = better(best, tryPack(plan, containers, "Plain-Rules" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new PlainPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							supportPolicy,
							hasDoorSideRule))
					.build(), deadline));
			return best;
		}
		best = better(best, tryMacroPacks(plan, containers, "Plain-MACRO-Y", swapLengthWidth,
				VolumeThenWeightBoxItemComparator.getInstance(), false, deadline));
		best = better(best, tryPack(plan, containers, "Plain-LAYOUT" + (swapLengthWidth ? "-SWAPPED" : ""),
				layoutPlain(VolumeThenWeightBoxItemComparator.getInstance()), deadline));
		best = better(best, tryPack(plan, containers, "LAFF" + (swapLengthWidth ? "-SWAPPED" : ""), LargestAreaFitFirstPackager.newBuilder().build(), deadline));
		best = better(best, tryPack(plan, containers, "FastLAFF" + (swapLengthWidth ? "-SWAPPED" : ""), FastLargestAreaFitFirstPackager.newBuilder().build(), deadline));
		String suffix = swapLengthWidth ? "-SWAPPED" : "";
		best = better(best, tryPack(plan, containers, "Plain-STABLE" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), new PlainPlacementComparator()), deadline));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-AREA" + suffix,
				stablePlain(new LargestAreaBoxItemComparator(), new PlainPlacementComparator()), deadline));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-Y" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(true)), deadline));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-X" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(false)), deadline));
		return best;
	}

	private PackagerResult tryBlockBeamSearch(PackingPlan plan, List<ContainerItem> containers,
			String label, long deadline) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) return null;
		long localDeadline = System.currentTimeMillis() + BLOCK_BEAM_SEARCH_MILLIS;
		if (deadline > 0L) localDeadline = Math.min(localDeadline, deadline);
		long started = System.nanoTime();
		PackagerResult result = new BlockBeamSearchPackager().pack(
				containers.get(0).getContainer(), plan.boxItems(), localDeadline);
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		if (result == null || !result.isSuccess()) {
			System.out.println("packing-service " + label + " success=false elapsedMs=" + elapsedMillis);
			return null;
		}
		PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
		boolean valid = support.valid() && BottomRuleSupport.isValid(result)
				&& NoPressRuleSupport.isValid(result);
		if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
			DoorSideRuleSupport.mirrorToDoorSide(result);
			valid = DoorSideRuleSupport.isValid(result);
		}
		System.out.println("packing-service " + label + " success=" + valid
				+ " placements=" + result.get(0).getStack().size() + " elapsedMs=" + elapsedMillis
				+ (support.valid() ? "" : " rejectedBox=" + support.boxId()
						+ " reason=" + support.reason() + " ratio=" + support.supportRatio()));
		return valid ? result : null;
	}

	private PackagerResult tryMacroPacks(PackingPlan plan, List<ContainerItem> containers,
			String label, boolean swapLengthWidth, Comparator<BoxItem> boxComparator,
			boolean preferDoorSidePlacements, long deadline) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) {
			return null;
		}
		PackagerResult best = null;
		Container container = containers.get(0).getContainer();
		int[][] cuboids = {{2, 2, 5}, {1, 4, 5}, {1, 2, 5}, {1, 1, 5}};
		int[] typeLimits = {Integer.MAX_VALUE, 4};
		for (int[] cuboid : cuboids) {
			for (int typeLimit : typeLimits) {
				MacroBlockPlanner.Plan macroPlan = MacroBlockPlanner.cuboid(plan.boxItems(), container,
						cuboid[0], cuboid[1], cuboid[2], typeLimit);
				best = better(best, tryMacroPack(plan, containers,
						label + "C" + cuboid[0] + "x" + cuboid[1] + "x" + cuboid[2]
								+ "T" + macroTypeLabel(typeLimit) + (swapLengthWidth ? "-SWAPPED" : ""),
						boxComparator, preferDoorSidePlacements, deadline, macroPlan));
			}
		}
		int[][] grids = {{2, 2}, {1, 4}, {1, 2}};
		for (int[] grid : grids) {
			MacroBlockPlanner.Plan macroPlan = MacroBlockPlanner.grid(
					plan.boxItems(), container, grid[0], grid[1]);
			best = better(best, tryMacroPack(plan, containers,
					label + "G" + grid[0] + "x" + grid[1] + (swapLengthWidth ? "-SWAPPED" : ""),
					boxComparator, preferDoorSidePlacements, deadline, macroPlan));
		}
		MacroBlockPlanner.Plan fullRows = MacroBlockPlanner.rowsAcrossY(
				plan.boxItems(), container, Integer.MAX_VALUE);
		best = better(best, tryMacroPack(plan, containers,
				label + "FULL" + (swapLengthWidth ? "-SWAPPED" : ""),
				boxComparator, preferDoorSidePlacements, deadline, fullRows));
		return best;
	}

	private static String macroTypeLabel(int maximumMacroTypes) {
		return maximumMacroTypes == Integer.MAX_VALUE ? "ALL" : Integer.toString(maximumMacroTypes);
	}

	private PackagerResult tryMacroPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Comparator<BoxItem> boxComparator, boolean preferDoorSidePlacements, long deadline,
			MacroBlockPlanner.Plan macroPlan) {
		if (!macroPlan.hasMacros()) {
			return null;
		}

		PlainPackager packager = PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						new LayoutPlacementComparator(), boxComparator, supportPolicy,
						preferDoorSidePlacements, true))
				.build();
		long started = System.nanoTime();
		PackingSearchDiagnostics.begin(diagnosticsEnabled);
		try {
			AbstractPackagerResultBuilder<?> builder = packager.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(1)
					.withBoxItems(cloneBoxItems(macroPlan.boxItems()));
			if (deadline > 0L) {
				builder.withDeadline(deadline);
			}
			PackagerResult packed = builder.build();
			PackagerResult result = packed.isSuccess() ? macroPlan.expand(packed) : packed;
			long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
			PackingSearchDiagnostics.Counters diagnostics = PackingSearchDiagnostics.finish();
			System.out.println("packing-service " + label + " success=" + result.isSuccess()
					+ " macroTypes=" + macroPlan.blocks().size()
					+ " placements=" + (result.isSuccess() ? result.get(0).getStack().size() : 0)
					+ " elapsedMs=" + elapsedMillis + " candidates=" + diagnostics.candidates()
					+ " rejected=" + diagnostics.rejections());
			if (!result.isSuccess()) {
				return null;
			}
			PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
			boolean valid = support.valid() && BottomRuleSupport.isValid(result)
					&& NoPressRuleSupport.isValid(result);
			if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				valid = DoorSideRuleSupport.isValid(result);
			}
			if (!valid && !support.valid()) {
				System.out.println("packing-service " + label + " expanded-result rejected box="
						+ support.boxId() + " reason=" + support.reason()
						+ " ratio=" + support.supportRatio());
			}
			return valid ? result : null;
		} finally {
			PackingSearchDiagnostics.finish();
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private PlainPackager layoutPlain(Comparator<BoxItem> boxComparator) {
		return PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						new LayoutPlacementComparator(), boxComparator, supportPolicy, false, true))
				.build();
	}

	private PlainPackager stablePlain(Comparator<BoxItem> boxComparator,
			Comparator<Placement> placementComparator) {
		return PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						placementComparator, boxComparator, supportPolicy))
				.build();
	}

	private static Comparator<Placement> floorFirst(boolean yBeforeX) {
		Comparator<Placement> lowerZ = Comparator.comparingInt(Placement::getAbsoluteZ).reversed();
		Comparator<Placement> lowerX = Comparator.comparingInt(Placement::getAbsoluteX).reversed();
		Comparator<Placement> lowerY = Comparator.comparingInt(Placement::getAbsoluteY).reversed();
		Comparator<Placement> position = yBeforeX
				? lowerZ.thenComparing(lowerY).thenComparing(lowerX)
				: lowerZ.thenComparing(lowerX).thenComparing(lowerY);
		return position.thenComparingLong(placement -> placement.getStackValue().getArea());
	}

	private PackagerResult tryPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Packager<? extends AbstractPackagerResultBuilder<?>> packager, long deadline) {
		long started = System.nanoTime();
		PackingSearchDiagnostics.begin(diagnosticsEnabled);
		try {
			AbstractPackagerResultBuilder<?> builder = packager
					.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(totalContainerCount(containers))
					.withBoxItems(cloneBoxItems(plan.boxItems()));
			if (deadline > 0L) {
				builder.withDeadline(deadline);
			}
			PackagerResult result = builder.build();

			long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
			PackingSearchDiagnostics.Counters diagnostics = PackingSearchDiagnostics.finish();
			int placements = result.getContainers().stream()
					.mapToInt(container -> container.getStack().getPlacements().size()).sum();
			System.out.println("packing-service " + label + " success=" + result.isSuccess()
					+ " containerCount=" + result.size() + " placements=" + placements
					+ " elapsedMs=" + elapsedMillis + " candidates=" + diagnostics.candidates()
					+ " rejected=" + diagnostics.rejections());
			PlacementSupport.Validation support = result.isSuccess()
					? PlacementSupport.validate(result, supportPolicy)
					: PlacementSupport.Validation.supported();
			if (!support.valid()) {
				System.out.println("packing-service " + label + " rejected unsupported box=" + support.boxId()
						+ " position=" + support.x() + "," + support.y() + "," + support.z()
						+ " ratio=" + support.supportRatio() + " reason=" + support.reason());
			}
			boolean valid = result.isSuccess() && support.valid()
					&& BottomRuleSupport.isValid(result) && NoPressRuleSupport.isValid(result);
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			if (valid && hasDoorSideRule) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				valid = DoorSideRuleSupport.isValid(result);
			}
			return valid ? result : null;
		} finally {
			PackingSearchDiagnostics.finish();
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static boolean hasBusinessRule(PackingPlan plan) {
		return plan.boxItems().stream().anyMatch(item ->
				BottomRuleSupport.hasBottomRule(item.getBox())
						|| NoPressRuleSupport.hasNoPressRule(item.getBox())
						|| DoorSideRuleSupport.hasDoorSideRule(item.getBox()));
	}

	private static List<ContainerItem> swappedContainers(List<ContainerItem> source) {
		return source.stream().map(item -> {
			Container original = item.getContainer();
			Container swapped = Container.newBuilder()
					.withId(original.getId())
					.withDescription(original.getDescription() + "-SWAPPED")
					.withSize(original.getDy(), original.getDx(), original.getDz())
					.withEmptyWeight(original.getEmptyWeight())
					.withMaxLoadWeight(original.getMaxLoadWeight())
					.build();
			return new ContainerItem(swapped, item.getCount());
		}).toList();
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) {
			return current;
		}
		if (current == null || candidate.size() < current.size()) {
			return candidate;
		}
		if (candidate.size() == current.size() && totalLoadVolume(candidate) > totalLoadVolume(current)) {
			return candidate;
		}
		if (candidate.size() == current.size() && totalLoadVolume(candidate) == totalLoadVolume(current)
				&& DoorSideRuleSupport.score(candidate).isBetterThan(DoorSideRuleSupport.score(current))) {
			return candidate;
		}
		return current;
	}

	private static int totalContainerCount(List<ContainerItem> containerItems) {
		int count = 0;
		for (ContainerItem item : containerItems) {
			count += item.getCount();
		}
		return count;
	}

	private static long totalLoadVolume(PackagerResult result) {
		return result.getContainers().stream().mapToLong(c -> c.getLoadVolume()).sum();
	}

	private static List<com.github.skjolber.packing.api.BoxItem> cloneBoxItems(List<com.github.skjolber.packing.api.BoxItem> items) {
		return items.stream().map(com.github.skjolber.packing.api.BoxItem::clone).toList();
	}
}
