package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.dto.PackingRequest;

class WholeOrderPackingCacheTest {

	@Test
	void reusesSuccessfulLayoutForAnEquivalentPhysicalContainer() throws Exception {
		PackingPlan plan = plan();
		Container first = plan.containerItems().get(0).getContainer();
		Container second = plan.containerItems().get(1).getContainer();
		BoxItem item = plan.boxItems().get(0);
		Container packed = first.clone();
		packed.getStack().add(new Placement(item.getBox().getStackValues()[0], 0, 10, 20, 30));

		WholeOrderPackingCache cache = new WholeOrderPackingCache();
		cache.putSuccess(first, List.of(item), new PackagerResult(List.of(packed), 123L, false), 125L);
		WholeOrderPackingCache.Lookup lookup = cache.lookup(second, List.of(item));

		assertThat(lookup.hit()).isTrue();
		assertThat(lookup.failed()).isFalse();
		assertThat(lookup.savedMillis()).isEqualTo(125L);
		assertThat(lookup.result().get(0).getId()).isEqualTo("C2");
		assertThat(lookup.result().get(0)).isNotSameAs(packed);
		assertThat(lookup.result().get(0).getStack().getPlacements()).singleElement().satisfies(placement -> {
			assertThat(placement.getAbsoluteX()).isEqualTo(10);
			assertThat(placement.getAbsoluteY()).isEqualTo(20);
			assertThat(placement.getAbsoluteZ()).isEqualTo(30);
			assertThat(placement.getBox().<String>getProperty(PackingMapper.PROP_CARGO_ID))
					.isEqualTo(item.getBox().<String>getProperty(PackingMapper.PROP_CARGO_ID));
		});
		assertThat(cache.stats().successHits()).isEqualTo(1);
	}

	@Test
	void preservesSwappedOrientationWhenRebindingTheContainerId() throws Exception {
		PackingPlan plan = plan();
		Container first = plan.containerItems().get(0).getContainer();
		Container second = plan.containerItems().get(1).getContainer();
		BoxItem item = plan.boxItems().get(0);
		Container swapped = Container.newBuilder()
				.withId(first.getId())
				.withDescription(first.getDescription() + "-SWAPPED")
				.withSize(first.getDy(), first.getDx(), first.getDz())
				.withLoadSize(first.getLoadDy(), first.getLoadDx(), first.getLoadDz())
				.withEmptyWeight(first.getEmptyWeight())
				.withMaxLoadWeight(first.getMaxLoadWeight())
				.build();
		swapped.getStack().add(new Placement(item.getBox().getStackValues()[0], 0, 0, 0, 0));
		WholeOrderPackingCache cache = new WholeOrderPackingCache();
		cache.putSuccess(first, List.of(item), new PackagerResult(List.of(swapped), 10L, false), 12L);

		WholeOrderPackingCache.Lookup lookup = cache.lookup(second, List.of(item));

		assertThat(lookup.result().get(0).getId()).isEqualTo("C2");
		assertThat(lookup.result().get(0).getLoadDx()).isEqualTo(first.getLoadDy());
		assertThat(lookup.result().get(0).getLoadDy()).isEqualTo(first.getLoadDx());
		assertThat(lookup.result().get(0).getDescription()).endsWith("-SWAPPED");
	}

	@Test
	void doesNotCacheFailureInterruptedByGlobalDeadline() throws Exception {
		PackingPlan plan = plan();
		Container container = plan.containerItems().get(0).getContainer();
		List<BoxItem> items = plan.boxItems();
		WholeOrderPackingCache cache = new WholeOrderPackingCache();

		cache.putFailure(container, items, 50L, false);

		assertThat(cache.lookup(container, items).hit()).isFalse();
		assertThat(cache.stats().size()).isZero();
	}

	@Test
	void matchesTheSameCargoCombinationRegardlessOfItemOrder() throws Exception {
		PackingPlan plan = plan();
		Container container = plan.containerItems().get(0).getContainer();
		List<BoxItem> reversed = new ArrayList<>(plan.boxItems());
		Collections.reverse(reversed);
		WholeOrderPackingCache cache = new WholeOrderPackingCache();

		cache.putFailure(container, plan.boxItems(), 75L, true);
		WholeOrderPackingCache.Lookup lookup = cache.lookup(container, reversed);

		assertThat(lookup.hit()).isTrue();
		assertThat(lookup.failed()).isTrue();
		assertThat(cache.stats().failureHits()).isEqualTo(1);
	}

	private static PackingPlan plan() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M-CACHE",
				  "containerLists":[
				    {"id":"C1","size":20,"type":"GP"},
				    {"id":"C2","size":20,"type":"GP"}
				  ],
				  "houseBillList":[
				    {"houseBsId":"H1","totalNum":1,"totalWeight":10,"totalMeas":1,
				     "items":[{"inboundId":"I1","num":1,"weight":10,"meas":1,
				       "size":{"length":100,"width":100,"height":100}}]},
				    {"houseBsId":"H2","totalNum":1,"totalWeight":10,"totalMeas":1,
				     "items":[{"inboundId":"I2","num":1,"weight":10,"meas":1,
				       "size":{"length":100,"width":100,"height":100}}]}
				  ]
				}
				""", PackingRequest.class);
		return new PackingMapper().toPlan(request);
	}
}
