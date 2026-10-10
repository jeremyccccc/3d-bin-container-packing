package com.github.skjolber.packing.service.service;

import static com.github.skjolber.packing.service.service.PackingDiagnostics.safe;

import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
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
import com.github.skjolber.packing.service.service.rule.BottomPlacementControlsBuilder;
import com.github.skjolber.packing.service.service.rule.BottomRuleSupport;
import com.github.skjolber.packing.service.service.rule.DoorSideRuleSupport;
import com.github.skjolber.packing.service.service.rule.LayoutPlacementComparator;
import com.github.skjolber.packing.service.service.rule.NoPressRuleSupport;
import com.github.skjolber.packing.service.service.rule.PackingBusinessRules;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;
import com.github.skjolber.packing.service.service.rule.RuleBoxItemComparator;
import com.github.skjolber.packing.service.service.logging.PackingSearchDiagnostics;

/** Compatibility pipeline used when Block Beam-only mode is disabled. */
final class LegacyContainerPackingStrategy {
	private final PlacementSupport.Policy supportPolicy;
	private final boolean diagnosticsEnabled;

	LegacyContainerPackingStrategy(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled) {
		this.supportPolicy = supportPolicy;
		this.diagnosticsEnabled = diagnosticsEnabled;
	}

	PackagerResult pack(PackingPlan plan, List<ContainerItem> containers, boolean swapped,
			long deadline, PackingLogContext context) {
		PackagerResult best = null;
		if (PackingBusinessRules.hasAny(plan)) {
			boolean doorSide = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			Comparator<BoxItem> boxes = doorSide
					? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance();
			best = better(best, tryMacroPacks(plan, containers, "Plain-Rules-MACRO-Y",
					swapped, boxes, doorSide, deadline, context));
			best = better(best, tryPack(plan, containers, "Plain-Rules-LAYOUT" + suffix(swapped),
					PlainPackager.newBuilder().withPlacementControlsBuilderFactory(() ->
							new BottomPlacementControlsBuilder(new LayoutPlacementComparator(), boxes,
									supportPolicy, doorSide, true)).build(), deadline, context));
			best = better(best, tryPack(plan, containers, "Plain-Rules" + suffix(swapped),
					PlainPackager.newBuilder().withPlacementControlsBuilderFactory(() ->
							new BottomPlacementControlsBuilder(new PlainPlacementComparator(), boxes,
									supportPolicy, doorSide)).build(), deadline, context));
			return best;
		}

		best = better(best, tryMacroPacks(plan, containers, "Plain-MACRO-Y", swapped,
				VolumeThenWeightBoxItemComparator.getInstance(), false, deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-LAYOUT" + suffix(swapped),
				layoutPlain(VolumeThenWeightBoxItemComparator.getInstance()), deadline, context));
		best = better(best, tryPack(plan, containers, "LAFF" + suffix(swapped),
				LargestAreaFitFirstPackager.newBuilder().build(), deadline, context));
		best = better(best, tryPack(plan, containers, "FastLAFF" + suffix(swapped),
				FastLargestAreaFitFirstPackager.newBuilder().build(), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE" + suffix(swapped),
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), new PlainPlacementComparator()),
				deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-AREA" + suffix(swapped),
				stablePlain(new LargestAreaBoxItemComparator(), new PlainPlacementComparator()), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-Y" + suffix(swapped),
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(true)), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-X" + suffix(swapped),
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(false)), deadline, context));
		return best;
	}

	private PackagerResult tryMacroPacks(PackingPlan plan, List<ContainerItem> containers,
			String label, boolean swapped, Comparator<BoxItem> boxComparator,
			boolean preferDoorSide, long deadline, PackingLogContext context) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) return null;
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
								+ "T" + macroTypeLabel(typeLimit) + suffix(swapped),
						boxComparator, preferDoorSide, deadline, macroPlan, context));
			}
		}
		int[][] grids = {{2, 2}, {1, 4}, {1, 2}};
		for (int[] grid : grids) {
			MacroBlockPlanner.Plan macroPlan = MacroBlockPlanner.grid(plan.boxItems(), container, grid[0], grid[1]);
			best = better(best, tryMacroPack(plan, containers,
					label + "G" + grid[0] + "x" + grid[1] + suffix(swapped),
					boxComparator, preferDoorSide, deadline, macroPlan, context));
		}
		MacroBlockPlanner.Plan fullRows = MacroBlockPlanner.rowsAcrossY(
				plan.boxItems(), container, Integer.MAX_VALUE);
		return better(best, tryMacroPack(plan, containers, label + "FULL" + suffix(swapped),
				boxComparator, preferDoorSide, deadline, fullRows, context));
	}

	private PackagerResult tryMacroPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Comparator<BoxItem> boxComparator, boolean preferDoorSide, long deadline,
			MacroBlockPlanner.Plan macroPlan, PackingLogContext context) {
		if (!macroPlan.hasMacros()) return null;
		PlainPackager packager = PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						new LayoutPlacementComparator(), boxComparator, supportPolicy, preferDoorSide, true))
				.build();
		long started = System.nanoTime();
		PackingSearchDiagnostics.begin(diagnosticsEnabled);
		try {
			AbstractPackagerResultBuilder<?> builder = packager.newResultBuilder()
					.withContainerItems(containers).withMaxContainerCount(1)
					.withBoxItems(cloneBoxItems(macroPlan.boxItems()));
			if (deadline > 0L) builder.withDeadline(deadline);
			PackagerResult packed = builder.build();
			PackagerResult result = packed.isSuccess() ? macroPlan.expand(packed) : packed;
			long elapsed = elapsed(started);
			PackingSearchDiagnostics.Counters diagnostics = PackingSearchDiagnostics.finish();
			if (!result.isSuccess()) {
				System.out.println("packing-service " + label + context.fields() + " success=false"
						+ " macroTypes=" + macroPlan.blocks().size() + " placements=0 elapsedMs=" + elapsed
						+ " candidates=" + diagnostics.candidates() + " rejected=" + diagnostics.rejections()
						+ " reason=" + deadlineReason(deadline));
				return null;
			}
			return validate(plan, result, label, context, elapsed, diagnostics, macroPlan.blocks().size());
		} finally {
			PackingSearchDiagnostics.finish();
			close(packager);
		}
	}

	private PackagerResult tryPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Packager<? extends AbstractPackagerResultBuilder<?>> packager, long deadline,
			PackingLogContext context) {
		long started = System.nanoTime();
		PackingSearchDiagnostics.begin(diagnosticsEnabled);
		try {
			AbstractPackagerResultBuilder<?> builder = packager.newResultBuilder()
					.withContainerItems(containers).withMaxContainerCount(totalContainerCount(containers))
					.withBoxItems(cloneBoxItems(plan.boxItems()));
			if (deadline > 0L) builder.withDeadline(deadline);
			PackagerResult result = builder.build();
			long elapsed = elapsed(started);
			PackingSearchDiagnostics.Counters diagnostics = PackingSearchDiagnostics.finish();
			int placements = result.getContainers().stream()
					.mapToInt(container -> container.getStack().getPlacements().size()).sum();
			PlacementSupport.Validation support = result.isSuccess()
					? PlacementSupport.validate(result, supportPolicy) : PlacementSupport.Validation.supported();
			boolean bottom = result.isSuccess() && BottomRuleSupport.isValid(result);
			boolean noPress = result.isSuccess() && NoPressRuleSupport.isValid(result);
			boolean valid = result.isSuccess() && support.valid() && bottom && noPress;
			boolean door = true;
			if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				door = DoorSideRuleSupport.isValid(result);
				valid = door;
			}
			System.out.println("packing-service " + label + context.fields() + " success=" + valid
					+ " packed=" + result.isSuccess() + " containerCount=" + result.size()
					+ " placements=" + placements + " elapsedMs=" + elapsed
					+ " candidates=" + diagnostics.candidates() + " rejected=" + diagnostics.rejections()
					+ " reason=" + (result.isSuccess()
							? validationReason(support, bottom, noPress, door) : deadlineReason(deadline)));
			return valid ? result : null;
		} finally {
			PackingSearchDiagnostics.finish();
			close(packager);
		}
	}

	private PackagerResult validate(PackingPlan plan, PackagerResult result, String label,
			PackingLogContext context, long elapsed, PackingSearchDiagnostics.Counters diagnostics,
			int macroTypes) {
		PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
		boolean bottom = BottomRuleSupport.isValid(result);
		boolean noPress = NoPressRuleSupport.isValid(result);
		boolean valid = support.valid() && bottom && noPress;
		boolean door = true;
		if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
			DoorSideRuleSupport.mirrorToDoorSide(result);
			door = DoorSideRuleSupport.isValid(result);
			valid = door;
		}
		System.out.println("packing-service " + label + context.fields() + " success=" + valid
				+ " macroTypes=" + macroTypes + " placements=" + result.get(0).getStack().size()
				+ " elapsedMs=" + elapsed + " candidates=" + diagnostics.candidates()
				+ " rejected=" + diagnostics.rejections()
				+ " reason=" + validationReason(support, bottom, noPress, door));
		return valid ? result : null;
	}

	private PlainPackager layoutPlain(Comparator<BoxItem> comparator) {
		return PlainPackager.newBuilder().withPlacementControlsBuilderFactory(() ->
				new BottomPlacementControlsBuilder(new LayoutPlacementComparator(), comparator,
						supportPolicy, false, true)).build();
	}

	private PlainPackager stablePlain(Comparator<BoxItem> boxes, Comparator<Placement> placements) {
		return PlainPackager.newBuilder().withPlacementControlsBuilderFactory(() ->
				new BottomPlacementControlsBuilder(placements, boxes, supportPolicy)).build();
	}

	private static Comparator<Placement> floorFirst(boolean yBeforeX) {
		Comparator<Placement> z = Comparator.comparingInt(Placement::getAbsoluteZ).reversed();
		Comparator<Placement> x = Comparator.comparingInt(Placement::getAbsoluteX).reversed();
		Comparator<Placement> y = Comparator.comparingInt(Placement::getAbsoluteY).reversed();
		return (yBeforeX ? z.thenComparing(y).thenComparing(x) : z.thenComparing(x).thenComparing(y))
				.thenComparingLong(placement -> placement.getStackValue().getArea());
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) return current;
		if (current == null || candidate.size() < current.size()) return candidate;
		if (candidate.size() == current.size() && capacity(candidate) < capacity(current)) return candidate;
		if (candidate.size() == current.size() && load(candidate) > load(current)) return candidate;
		if (candidate.size() == current.size() && load(candidate) == load(current)
				&& DoorSideRuleSupport.score(candidate).isBetterThan(DoorSideRuleSupport.score(current))) return candidate;
		return current;
	}

	private static String validationReason(PlacementSupport.Validation support,
			boolean bottom, boolean noPress, boolean door) {
		if (!support.valid()) return "support-" + safe(support.reason());
		if (!bottom) return "bottom-rule";
		if (!noPress) return "no-press-rule";
		if (!door) return "door-side-rule";
		return "none";
	}

	private static String suffix(boolean swapped) { return swapped ? "-SWAPPED" : ""; }
	private static String macroTypeLabel(int limit) { return limit == Integer.MAX_VALUE ? "ALL" : Integer.toString(limit); }
	private static String deadlineReason(long deadline) { return deadline > 0L && System.currentTimeMillis() >= deadline ? "deadline" : "no-placement"; }
	private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }
	private static long load(PackagerResult result) { return result.getContainers().stream().mapToLong(Container::getLoadVolume).sum(); }
	private static long capacity(PackagerResult result) { return result.getContainers().stream().mapToLong(Container::getMaxLoadVolume).sum(); }
	private static int totalContainerCount(List<ContainerItem> items) { return items.stream().mapToInt(ContainerItem::getCount).sum(); }
	private static List<BoxItem> cloneBoxItems(List<BoxItem> items) { return items.stream().map(BoxItem::clone).toList(); }

	private static void close(Packager<?> packager) {
		try { packager.close(); } catch (Exception e) { throw new IllegalStateException(e); }
	}
}
