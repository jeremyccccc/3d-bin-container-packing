package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
	private static final AtomicLong SEARCH_SEQUENCE = new AtomicLong();

	private final WholeOrderAssignmentSolver assignmentSolver = new WholeOrderAssignmentSolver();
	private final PlacementSupport.Policy supportPolicy;
	private final boolean diagnosticsEnabled;
	private final boolean blockBeamOnly;
	private final WholeOrderAssignmentStrategy assignmentStrategy;
	private final double assignmentTargetFillRatio;

	PackingEngine() {
		this(PlacementSupport.DEFAULT_POLICY, false, false,
				WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	@Autowired
	PackingEngine(
			@Value("${packing.support.minimum-area-ratio:0.85}") double minimumAreaRatio,
			@Value("${packing.support.require-center:true}") boolean requireCenterSupport,
			@Value("${packing.support.maximum-overhang-mm:20}") int maximumOverhangMillimeters,
			@Value("${packing.support.maximum-overhang-ratio:0.05}") double maximumOverhangRatio,
			@Value("${packing.diagnostics.enabled:false}") boolean diagnosticsEnabled,
			@Value("${packing.block-beam-only:false}") boolean blockBeamOnly,
			@Value("${packing.assignment.strategy:balanced}") String assignmentStrategy,
			@Value("${packing.assignment.target-fill-ratio:0.85}") double assignmentTargetFillRatio) {
		this(new PlacementSupport.Policy(minimumAreaRatio, requireCenterSupport,
				maximumOverhangMillimeters, maximumOverhangRatio), diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.parse(assignmentStrategy), assignmentTargetFillRatio);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy) {
		this(supportPolicy, false, false, WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled) {
		this(supportPolicy, diagnosticsEnabled, false, WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly) {
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio) {
		if (assignmentTargetFillRatio <= 0.0 || assignmentTargetFillRatio > 1.0) {
			throw new IllegalArgumentException("packing.assignment.target-fill-ratio must be in (0, 1]");
		}
		this.supportPolicy = supportPolicy;
		this.diagnosticsEnabled = diagnosticsEnabled;
		this.blockBeamOnly = blockBeamOnly;
		this.assignmentStrategy = assignmentStrategy;
		this.assignmentTargetFillRatio = assignmentTargetFillRatio;
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
		long searchStarted = System.nanoTime();
		long deadline = System.currentTimeMillis() + WHOLE_ORDER_SEARCH_MILLIS;
		long searchId = SEARCH_SEQUENCE.incrementAndGet();
		List<ContainerItem> physicalContainers = expandContainerItems(plan.containerItems());
		long candidateGenerationStarted = System.nanoTime();
		List<WholeOrderAssignmentSolver.Candidate> candidates = assignmentSolver.candidates(
				plan, assignmentStrategy, assignmentTargetFillRatio);
		long candidateGenerationMillis = elapsedMillis(candidateGenerationStarted);
		WholeOrderPackingCache cache = new WholeOrderPackingCache();
		System.out.println("packing-service whole-order search-start searchId=" + searchId
				+ " candidates=" + candidates.size() + " candidateGenerationMs=" + candidateGenerationMillis
				+ " containers=" + physicalContainers.size() + " houseBills=" + houseBillCount(plan.boxItems())
				+ " itemTypes=" + plan.boxItems().size() + " units=" + unitCount(plan.boxItems())
				+ " deadlineMs=" + WHOLE_ORDER_SEARCH_MILLIS
				+ " assignmentStrategy=" + assignmentStrategy.propertyValue()
				+ " targetFillRatio=" + assignmentTargetFillRatio);
		PackagerResult best = null;
		int attempted = 0;
		int successful = 0;
		long stopAt = deadline;
		for (WholeOrderAssignmentSolver.Candidate candidate : candidates) {
			if (System.currentTimeMillis() >= stopAt) {
				break;
			}
			attempted++;
			long candidateStarted = System.nanoTime();
			System.out.println("packing-service whole-order candidate-start searchId=" + searchId
					+ " attempt=" + attempted + " source=" + safe(candidate.source())
					+ " generationIndex=" + (candidate.generationIndex() + 1)
					+ candidate.score().logFields()
					+ " containers=" + candidate.itemsByContainer().size());
			List<Container> packedContainers = new ArrayList<>();
			long duration = 0L;
			boolean success = true;
			int containersAttempted = 0;
			int failedContainer = -1;
			String failureReason = "none";
			for (int i : candidate.score().validationOrder()) {
				List<com.github.skjolber.packing.api.BoxItem> items = candidate.itemsByContainer().get(i);
				if (items.isEmpty()) {
					System.out.println("packing-service whole-order container-skip searchId=" + searchId
							+ " attempt=" + attempted + " source=" + safe(candidate.source())
							+ " container=" + (i + 1) + "/" + physicalContainers.size()
							+ " containerId=" + safe(physicalContainers.get(i).getContainer().getId())
							+ " reason=empty");
					continue;
				}
				containersAttempted++;
				Container container = physicalContainers.get(i).getContainer();
				LogContext context = new LogContext(searchId, attempted, candidate.source(),
						i + 1, physicalContainers.size(), container.getId());
				long containerStarted = System.nanoTime();
				System.out.println("packing-service whole-order container-start" + context.fields()
						+ " validationRank=" + containersAttempted
						+ " predictedRisk=" + rounded(candidate.score().containerRisks().get(i))
						+ " houseBills=" + houseBillCount(items) + " itemTypes=" + items.size()
						+ " units=" + unitCount(items) + " volume=" + totalVolume(items)
						+ " weight=" + totalWeight(items)
						+ " volumeFillPct=" + percent(totalVolume(items), container.getMaxLoadVolume())
						+ " weightFillPct=" + percent(totalWeight(items), container.getMaxLoadWeight())
						+ (diagnosticsEnabled ? " houseBillIds=" + houseBillIds(items) : ""));
				PackingPlan containerPlan = new PackingPlan(
						plan.requestedContainers(),
						List.of(physicalContainers.get(i)),
						items,
						plan.cargoLines(),
						plan.warnings());
				WholeOrderPackingCache.Lookup cached = cache.lookup(container, items);
				PackagerResult packed;
				if (cached.hit()) {
					System.out.println("packing-service whole-order cache-hit" + context.fields()
							+ " status=" + (cached.failed() ? "failure" : "success")
							+ " savedMs=" + cached.savedMillis());
					packed = cached.result();
				} else {
					System.out.println("packing-service whole-order cache-miss" + context.fields());
					long packingStarted = System.nanoTime();
					packed = packFlat(containerPlan, deadline, context);
					long computationMillis = elapsedMillis(packingStarted);
					if (packed != null && packed.isSuccess() && packed.size() == 1) {
						cache.putSuccess(container, items, packed, computationMillis);
					} else {
						// Leave globally interrupted work uncached. A later candidate might reach
						// the same load with enough time to complete its algorithm sequence.
						boolean completed = System.currentTimeMillis() + 1_000L < deadline;
						cache.putFailure(container, items, computationMillis, completed);
					}
				}
				if (packed == null || !packed.isSuccess() || packed.size() != 1) {
					success = false;
					failedContainer = i + 1;
					failureReason = cached.hit() && cached.failed() ? "cached-failure"
							: System.currentTimeMillis() >= deadline ? "deadline" : "no-algorithm-succeeded";
					System.out.println("packing-service whole-order container-end" + context.fields()
							+ " success=false elapsedMs=" + elapsedMillis(containerStarted)
							+ " reason=" + failureReason);
					break;
				}
				packedContainers.add(packed.get(0));
				duration += packed.getDuration();
				System.out.println("packing-service whole-order container-end" + context.fields()
						+ " success=true elapsedMs=" + elapsedMillis(containerStarted)
						+ " placements=" + packed.get(0).getStack().size());
			}
			if (success) {
				PackagerResult packed = new PackagerResult(packedContainers, duration, false);
				if (!splitsHouseBills(packed)) {
					successful++;
					System.out.println("packing-service whole-order success source=" + candidate.source()
							+ " attempt=" + attempted + " containerCount=" + packed.size());
					best = better(best, packed);
					if (assignmentStrategy == WholeOrderAssignmentStrategy.FILL_FIRST) {
						stopAt = System.currentTimeMillis();
					} else if (successful == 1) {
						stopAt = Math.min(deadline, System.currentTimeMillis() + BETTER_SOLUTION_SEARCH_MILLIS);
					}
				} else {
					success = false;
					failureReason = "split-house-bill";
				}
			}
			System.out.println("packing-service whole-order candidate-end searchId=" + searchId
					+ " attempt=" + attempted + " source=" + safe(candidate.source())
					+ " success=" + success + " containersAttempted=" + containersAttempted
					+ " failedContainer=" + failedContainer + " reason=" + failureReason
					+ " elapsedMs=" + elapsedMillis(candidateStarted));
		}
		WholeOrderPackingCache.Stats cacheStats = cache.stats();
		System.out.println("packing-service whole-order candidates=" + candidates.size()
				+ " searchId=" + searchId + " attempted=" + attempted + " successful=" + successful
				+ " success=" + (best != null) + " candidateGenerationMs=" + candidateGenerationMillis
				+ " elapsedMs=" + elapsedMillis(searchStarted)
				+ " cacheHits=" + cacheStats.hits() + " cacheMisses=" + cacheStats.misses()
				+ " cacheSuccessHits=" + cacheStats.successHits()
				+ " cacheFailureHits=" + cacheStats.failureHits() + " cacheSize=" + cacheStats.size()
				+ " cacheSavedMs=" + cacheStats.savedMillis()
				+ " termination=" + terminationReason(candidates.size(), attempted, successful,
						deadline, assignmentStrategy));
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
		return packFlat(plan, deadline, LogContext.NONE);
	}

	private PackagerResult packFlat(PackingPlan plan, long deadline, LogContext context) {
		PackagerResult best = null;
		best = better(best, packOrientation(plan, false, deadline, context));
		if (best != null && best.isSuccess() && best.size() == 1) {
			return best;
		}
		best = better(best, packOrientation(plan, true, deadline, context));
		return best;
	}

	private PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth, long deadline,
			LogContext context) {
		List<ContainerItem> containers = swapLengthWidth ? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult best = null;
		PackagerResult blockBeam = tryBlockBeamSearch(plan, containers,
				"BlockBeam" + (swapLengthWidth ? "-SWAPPED" : ""), deadline, context);
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
					hasDoorSideRule, deadline, context));
			best = better(best, tryPack(plan, containers, "Plain-Rules-LAYOUT" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new LayoutPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							supportPolicy,
							hasDoorSideRule,
							true))
					.build(), deadline, context));
			best = better(best, tryPack(plan, containers, "Plain-Rules" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new PlainPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							supportPolicy,
							hasDoorSideRule))
					.build(), deadline, context));
			return best;
		}
		best = better(best, tryMacroPacks(plan, containers, "Plain-MACRO-Y", swapLengthWidth,
				VolumeThenWeightBoxItemComparator.getInstance(), false, deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-LAYOUT" + (swapLengthWidth ? "-SWAPPED" : ""),
				layoutPlain(VolumeThenWeightBoxItemComparator.getInstance()), deadline, context));
		best = better(best, tryPack(plan, containers, "LAFF" + (swapLengthWidth ? "-SWAPPED" : ""), LargestAreaFitFirstPackager.newBuilder().build(), deadline, context));
		best = better(best, tryPack(plan, containers, "FastLAFF" + (swapLengthWidth ? "-SWAPPED" : ""), FastLargestAreaFitFirstPackager.newBuilder().build(), deadline, context));
		String suffix = swapLengthWidth ? "-SWAPPED" : "";
		best = better(best, tryPack(plan, containers, "Plain-STABLE" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), new PlainPlacementComparator()), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-AREA" + suffix,
				stablePlain(new LargestAreaBoxItemComparator(), new PlainPlacementComparator()), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-Y" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(true)), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-X" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(false)), deadline, context));
		return best;
	}

	private PackagerResult tryBlockBeamSearch(PackingPlan plan, List<ContainerItem> containers,
			String label, long deadline, LogContext context) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) return null;
		BlockBeamSearchPackager packager = new BlockBeamSearchPackager();
		BlockBeamSearchPackager.SearchSession session = packager.newSession(
				containers.get(0).getContainer(), plan.boxItems(), context.fields());
		if (session == null) return null;
		long remainingMillis = Math.max(0L,
				BLOCK_BEAM_SEARCH_MILLIS - session.progress().activeMillis());
		long localDeadline = System.currentTimeMillis() + remainingMillis;
		if (deadline > 0L) localDeadline = Math.min(localDeadline, deadline);
		long started = System.nanoTime();
		BlockBeamSearchPackager.SearchProgress progress = session.advance(localDeadline);
		PackagerResult result = progress.result();
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		if (result == null || !result.isSuccess()) {
			System.out.println("packing-service " + label + context.fields()
					+ " success=false elapsedMs=" + elapsedMillis
					+ " reason=" + (progress.exhausted() ? "search-exhausted" : "deadline")
					+ " cumulativeMs=" + progress.activeMillis()
					+ " completion=" + rounded(progress.completionRatio()));
			return null;
		}
		PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
		boolean valid = support.valid() && BottomRuleSupport.isValid(result)
				&& NoPressRuleSupport.isValid(result);
		if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
			DoorSideRuleSupport.mirrorToDoorSide(result);
			valid = DoorSideRuleSupport.isValid(result);
		}
		System.out.println("packing-service " + label + context.fields() + " success=" + valid
				+ " placements=" + result.get(0).getStack().size() + " elapsedMs=" + elapsedMillis
				+ (support.valid() ? "" : " rejectedBox=" + support.boxId()
						+ " reason=" + support.reason() + " ratio=" + support.supportRatio()));
		return valid ? result : null;
	}

	private PackagerResult tryMacroPacks(PackingPlan plan, List<ContainerItem> containers,
			String label, boolean swapLengthWidth, Comparator<BoxItem> boxComparator,
			boolean preferDoorSidePlacements, long deadline, LogContext context) {
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
						boxComparator, preferDoorSidePlacements, deadline, macroPlan, context));
			}
		}
		int[][] grids = {{2, 2}, {1, 4}, {1, 2}};
		for (int[] grid : grids) {
			MacroBlockPlanner.Plan macroPlan = MacroBlockPlanner.grid(
					plan.boxItems(), container, grid[0], grid[1]);
			best = better(best, tryMacroPack(plan, containers,
					label + "G" + grid[0] + "x" + grid[1] + (swapLengthWidth ? "-SWAPPED" : ""),
					boxComparator, preferDoorSidePlacements, deadline, macroPlan, context));
		}
		MacroBlockPlanner.Plan fullRows = MacroBlockPlanner.rowsAcrossY(
				plan.boxItems(), container, Integer.MAX_VALUE);
		best = better(best, tryMacroPack(plan, containers,
				label + "FULL" + (swapLengthWidth ? "-SWAPPED" : ""),
				boxComparator, preferDoorSidePlacements, deadline, fullRows, context));
		return best;
	}

	private static String macroTypeLabel(int maximumMacroTypes) {
		return maximumMacroTypes == Integer.MAX_VALUE ? "ALL" : Integer.toString(maximumMacroTypes);
	}

	private PackagerResult tryMacroPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Comparator<BoxItem> boxComparator, boolean preferDoorSidePlacements, long deadline,
			MacroBlockPlanner.Plan macroPlan, LogContext context) {
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
			if (!result.isSuccess()) {
				System.out.println("packing-service " + label + context.fields() + " success=false"
						+ " macroTypes=" + macroPlan.blocks().size() + " placements=0"
						+ " elapsedMs=" + elapsedMillis + " candidates=" + diagnostics.candidates()
						+ " rejected=" + diagnostics.rejections() + " reason=" + deadlineReason(deadline));
				return null;
			}
			PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
			boolean bottomValid = BottomRuleSupport.isValid(result);
			boolean noPressValid = NoPressRuleSupport.isValid(result);
			boolean valid = support.valid() && bottomValid && noPressValid;
			boolean doorValid = true;
			if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				doorValid = DoorSideRuleSupport.isValid(result);
				valid = doorValid;
			}
			System.out.println("packing-service " + label + context.fields() + " success=" + valid
					+ " macroTypes=" + macroPlan.blocks().size()
					+ " placements=" + result.get(0).getStack().size()
					+ " elapsedMs=" + elapsedMillis + " candidates=" + diagnostics.candidates()
					+ " rejected=" + diagnostics.rejections()
					+ " reason=" + validationReason(support, bottomValid, noPressValid, doorValid));
			if (!valid && !support.valid()) {
				System.out.println("packing-service " + label + context.fields() + " expanded-result rejected box="
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
			Packager<? extends AbstractPackagerResultBuilder<?>> packager, long deadline,
			LogContext context) {
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
			PlacementSupport.Validation support = result.isSuccess()
					? PlacementSupport.validate(result, supportPolicy)
					: PlacementSupport.Validation.supported();
			if (!support.valid()) {
				System.out.println("packing-service " + label + context.fields()
						+ " rejected unsupported box=" + support.boxId()
						+ " position=" + support.x() + "," + support.y() + "," + support.z()
						+ " ratio=" + support.supportRatio() + " reason=" + support.reason());
			}
			boolean bottomValid = result.isSuccess() && BottomRuleSupport.isValid(result);
			boolean noPressValid = result.isSuccess() && NoPressRuleSupport.isValid(result);
			boolean valid = result.isSuccess() && support.valid() && bottomValid && noPressValid;
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			boolean doorValid = true;
			if (valid && hasDoorSideRule) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				doorValid = DoorSideRuleSupport.isValid(result);
				valid = doorValid;
			}
			String reason = result.isSuccess()
					? validationReason(support, bottomValid, noPressValid, doorValid)
					: deadlineReason(deadline);
			System.out.println("packing-service " + label + context.fields() + " success=" + valid
					+ " packed=" + result.isSuccess() + " containerCount=" + result.size()
					+ " placements=" + placements + " elapsedMs=" + elapsedMillis
					+ " candidates=" + diagnostics.candidates() + " rejected=" + diagnostics.rejections()
					+ " reason=" + reason);
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
		if (candidate.size() == current.size()
				&& totalContainerCapacity(candidate) < totalContainerCapacity(current)) {
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

	private static long totalContainerCapacity(PackagerResult result) {
		return result.getContainers().stream().mapToLong(Container::getMaxLoadVolume).sum();
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	private static int unitCount(List<BoxItem> items) {
		return items.stream().mapToInt(BoxItem::getCount).sum();
	}

	private static long totalVolume(List<BoxItem> items) {
		return items.stream().mapToLong(BoxItem::getVolume).sum();
	}

	private static long totalWeight(List<BoxItem> items) {
		return items.stream().mapToLong(BoxItem::getWeight).sum();
	}

	private static int houseBillCount(List<BoxItem> items) {
		Set<String> ids = new HashSet<>();
		for (BoxItem item : items) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			ids.add(id == null || id.isBlank() ? "<anonymous>" : id);
		}
		return ids.size();
	}

	private static String houseBillIds(List<BoxItem> items) {
		Set<String> ids = new HashSet<>();
		for (BoxItem item : items) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			ids.add(safe(id == null || id.isBlank() ? "<anonymous>" : id));
		}
		return String.join(",", ids.stream().sorted().toList());
	}

	private static double percent(long value, long maximum) {
		if (maximum <= 0L) return 0.0;
		return Math.round(value * 10_000.0 / maximum) / 100.0;
	}

	private static double rounded(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private static String safe(String value) {
		if (value == null || value.isBlank()) return "unknown";
		return value.replaceAll("\\s+", "_");
	}

	private static String deadlineReason(long deadline) {
		return deadline > 0L && System.currentTimeMillis() >= deadline ? "deadline" : "no-placement";
	}

	private static String validationReason(PlacementSupport.Validation support,
			boolean bottomValid, boolean noPressValid, boolean doorValid) {
		if (!support.valid()) return "support-" + safe(support.reason());
		if (!bottomValid) return "bottom-rule";
		if (!noPressValid) return "no-press-rule";
		if (!doorValid) return "door-side-rule";
		return "none";
	}

	private static String terminationReason(int candidates, int attempted, int successful, long deadline,
			WholeOrderAssignmentStrategy strategy) {
		if (System.currentTimeMillis() >= deadline) return "deadline";
		if (successful > 0 && attempted < candidates) {
			return strategy == WholeOrderAssignmentStrategy.FILL_FIRST
					? "first-success" : "better-solution-window";
		}
		if (attempted >= candidates) return "candidates-exhausted";
		return "stopped";
	}

	private record LogContext(long searchId, int attempt, String source,
			int containerIndex, int containerCount, String containerId) {
		private static final LogContext NONE = new LogContext(0L, 0, "", 0, 0, "");

		private String fields() {
			if (searchId == 0L) return "";
			return " searchId=" + searchId + " attempt=" + attempt + " source=" + safe(source)
					+ " container=" + containerIndex + "/" + containerCount
					+ " containerId=" + safe(containerId);
		}
	}

	private static List<com.github.skjolber.packing.api.BoxItem> cloneBoxItems(List<com.github.skjolber.packing.api.BoxItem> items) {
		return items.stream().map(com.github.skjolber.packing.api.BoxItem::clone).toList();
	}
}
