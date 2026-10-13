import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";

const canvas = document.getElementById("viewer");
const masterTitleEl = document.getElementById("masterTitle");
const statusEl = document.getElementById("status");
const overallContainerCountEl = document.getElementById("overallContainerCount");
const overallPieceCountEl = document.getElementById("overallPieceCount");
const containerListEl = document.getElementById("containerList");
const legendEl = document.getElementById("legend");
const containerListCountEl = document.getElementById("containerListCount");
const houseListCountEl = document.getElementById("houseListCount");
const containerInfoEl = document.getElementById("containerInfo");
const containerInfoBodyEl = document.getElementById("containerInfoBody");
const detailPanelEl = document.getElementById("detailPanel");
const detailBodyEl = document.getElementById("detailBody");
const hoverTooltipEl = document.getElementById("hoverTooltip");

// First 64 colors from Colorcet's constrained Glasbey palette
// (minimum chroma 20, maximum perceptual lightness 70). Keep the generated
// values intact: post-processing in HSL would reduce their perceptual spacing.
const palette = [
	"#d60000", "#8c3bff", "#018700", "#00acc6", "#e6a500", "#ff7ed1", "#6b004f", "#573b00",
	"#005659", "#15e18c", "#0000dd", "#a17569", "#bcb6ff", "#bf03b8", "#645472", "#790000",
	"#0774d8", "#729a7c", "#ff7752", "#004b00", "#8e7b01", "#f2007b", "#8eba00", "#a57bb8",
	"#5901a3", "#e2afaf", "#a03a52", "#a1c8c8", "#9e4b00", "#546744", "#bac389", "#5e7b87",
	"#60383b", "#8287ff", "#380000", "#e252ff", "#2f5282", "#7ecaff", "#c4668e", "#008069",
	"#919eb6", "#cc7407", "#7e2a8e", "#00bda3", "#2db152", "#4d33ff", "#00e400", "#ff00cd",
	"#c85748", "#e49cff", "#1ca1ff", "#6e70aa", "#c89a69", "#77563b", "#03dae6", "#c1a3c3",
	"#ff6989", "#ba00fd", "#915280", "#9e0174", "#93a14f", "#364424", "#af6dff", "#596d00"
];
const dimColor = new THREE.Color("#d8dde1");
const selectionBlue = new THREE.Color("#2563eb");
const selectionWhite = new THREE.Color("#ffffff");
const doorColor = new THREE.Color("#cf7a24");
const unitBoxGeometry = new THREE.BoxGeometry(1, 1, 1);
const identityQuaternion = new THREE.Quaternion();
const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();

const state = {
	data: null,
	selectedIndex: 0,
	selectedHouse: null,
	selectedPlacement: null,
	root: null,
	cargoGroups: [],
	cargoMeshes: [],
	selectionOutline: null,
	houseColors: new Map(),
	frustumHeight: 1000,
	pointerDown: null,
	hoveredPlacement: null
};

let renderer;
let scene;
let camera;
let controls;
let keyLight;

function initializeThree() {
	renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
	renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
	renderer.setClearColor(0xf6f7f9, 1);
	renderer.outputColorSpace = THREE.SRGBColorSpace;
	renderer.shadowMap.enabled = true;
	renderer.shadowMap.type = THREE.PCFSoftShadowMap;

	scene = new THREE.Scene();
	scene.background = new THREE.Color(0xf6f7f9);
	camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100000);

	controls = new OrbitControls(camera, canvas);
	controls.enableDamping = false;
	controls.screenSpacePanning = true;
	controls.minZoom = 0.18;
	controls.maxZoom = 8;
	controls.minPolarAngle = 0.06;
	controls.maxPolarAngle = Math.PI * 0.94;
	controls.mouseButtons.LEFT = THREE.MOUSE.ROTATE;
	controls.mouseButtons.MIDDLE = THREE.MOUSE.DOLLY;
	controls.mouseButtons.RIGHT = THREE.MOUSE.PAN;

	scene.add(new THREE.HemisphereLight(0xffffff, 0xb5bec5, 2.2));
	keyLight = new THREE.DirectionalLight(0xffffff, 1.35);
	keyLight.castShadow = true;
	keyLight.shadow.mapSize.set(2048, 2048);
	keyLight.shadow.bias = -0.00015;
	keyLight.shadow.normalBias = 1.5;
	keyLight.shadow.radius = 3;
	scene.add(keyLight);
	scene.add(keyLight.target);
	const fillLight = new THREE.DirectionalLight(0xdde9f1, 0.65);
	fillLight.position.set(1.2, 0.5, -1);
	scene.add(fillLight);

	resize();
	renderer.setAnimationLoop(() => {
		controls.update();
		updateDetailPosition();
		renderer.render(scene, camera);
	});
}

function resultIdFromPath() {
	const match = window.location.pathname.match(/\/packing-viewer\/([^/?#]+)/);
	return match ? decodeURIComponent(match[1]) : "";
}

function stableHash(text) {
	let hash = 0;
	for (let i = 0; i < text.length; i++) hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0;
	return hash >>> 0;
}

function fallbackCargoColor(value) {
	const color = new THREE.Color(value);
	return `#${color.getHexString()}`;
}

function buildHouseColors(containers) {
	const houses = new Set();
	for (const container of containers) {
		for (const placement of container.stack?.placements || []) houses.add(houseForPlacement(placement));
	}
	const ordered = Array.from(houses).sort((left, right) => stableHash(left) - stableHash(right) || left.localeCompare(right));
	const available = new Set(palette.map((_, index) => index));
	state.houseColors = new Map();
	for (const house of ordered) {
		const hash = stableHash(house);
		let index = hash % palette.length;
		for (let probe = 0; probe < palette.length && !available.has(index); probe++) index = (index + 13) % palette.length;
		if (available.has(index)) {
			available.delete(index);
			state.houseColors.set(house, fallbackCargoColor(palette[index]));
		} else {
			const hue = Math.round((hash * 137.508) % 360);
			const lightness = 48 + (hash % 3) * 7;
			state.houseColors.set(house, fallbackCargoColor(`hsl(${hue}, 62%, ${lightness}%)`));
		}
	}
}

function colorFor(text) {
	return state.houseColors.get(text) || fallbackCargoColor(palette[stableHash(text) % palette.length]);
}

function houseForPlacement(placement) {
	const stackable = placement?.stackable || {};
	return placement?.houseBsNo || placement?.houseBsId || (stackable.name || "").split(" ")[0] || stackable.id || "cargo";
}

function formatCm(value) {
	return Number.isFinite(Number(value)) ? `${Number((Number(value) / 10).toFixed(1))} cm` : "-";
}

function createStatsItem(label, value, unit) {
	const itemEl = document.createElement("div");
	itemEl.className = "stats-item";
	const mainEl = document.createElement("div");
	mainEl.className = "stats-main";
	const valueEl = document.createElement("span");
	valueEl.className = "stats-value";
	valueEl.textContent = value;
	const unitEl = document.createElement("span");
	unitEl.className = "stats-unit";
	unitEl.textContent = unit;
	const labelEl = document.createElement("span");
	labelEl.className = "stats-label";
	labelEl.textContent = label;
	mainEl.append(valueEl, unitEl);
	itemEl.append(mainEl, labelEl);
	return itemEl;
}

function addDetailRow(label, value) {
	const labelEl = document.createElement("div");
	labelEl.className = "detail-label";
	labelEl.textContent = label;
	const valueEl = document.createElement("div");
	valueEl.className = "detail-value";
	valueEl.textContent = value ?? "-";
	detailBodyEl.append(labelEl, valueEl);
}

function disposeRoot() {
	if (!state.root) return;
	state.root.traverse(object => {
		if (object.geometry && object.geometry !== unitBoxGeometry) object.geometry.dispose();
		const materials = Array.isArray(object.material) ? object.material : (object.material ? [object.material] : []);
		materials.forEach(material => {
			if (material.map) material.map.dispose();
			material.dispose();
		});
	});
	scene.remove(state.root);
	state.root = null;
	state.cargoGroups = [];
	state.cargoMeshes = [];
	state.selectionOutline = null;
}

function addGround(dx, dy, dz) {
	const margin = Math.max(dy * 0.55, dz * 0.7, 450);
	const groundWidth = dx + margin * 2;
	const groundDepth = dy + margin * 2;
	const groundX = dx / 2;
	const groundZ = dy / 2;

	const shadowGround = new THREE.Mesh(
		new THREE.PlaneGeometry(groundWidth, groundDepth),
		new THREE.ShadowMaterial({ color:0x25313a, transparent:true, opacity:0.105, depthWrite:false })
	);
	shadowGround.rotation.x = -Math.PI / 2;
	shadowGround.position.set(groundX, -2.2, groundZ);
	shadowGround.receiveShadow = true;
	shadowGround.userData.ignorePicking = true;
	state.root.add(shadowGround);

	const gridSize = Math.max(groundWidth, groundDepth);
	const divisions = Math.max(12, Math.min(36, Math.round(gridSize / 500)));
	const grid = new THREE.GridHelper(gridSize, divisions, 0xb9c2c9, 0xd6dce1);
	grid.position.set(groundX, -1.4, groundZ);
	grid.material.transparent = true;
	grid.material.opacity = 0.42;
	grid.material.depthWrite = false;
	grid.userData.ignorePicking = true;
	state.root.add(grid);

	keyLight.position.set(dx * 0.25, Math.max(dx, dy) * 0.8 + dz * 1.5, dy * 1.4);
	keyLight.target.position.set(dx / 2, 0, dy / 2);
	const shadowSpan = Math.max(dx, dy) * 0.72 + margin;
	const shadowCamera = keyLight.shadow.camera;
	shadowCamera.left = -shadowSpan;
	shadowCamera.right = shadowSpan;
	shadowCamera.top = shadowSpan;
	shadowCamera.bottom = -shadowSpan;
	shadowCamera.near = 10;
	shadowCamera.far = Math.max(dx, dy, dz) * 4;
	shadowCamera.updateProjectionMatrix();
	keyLight.shadow.needsUpdate = true;
}

function createGroundLabel(text, width) {
	const labelCanvas = document.createElement("canvas");
	labelCanvas.width = 256;
	labelCanvas.height = 128;
	const context = labelCanvas.getContext("2d");
	context.clearRect(0, 0, labelCanvas.width, labelCanvas.height);
	context.fillStyle = "#475569";
	context.font = "700 82px system-ui, sans-serif";
	context.textAlign = "center";
	context.textBaseline = "middle";
	context.fillText(text, 128, 66);
	const texture = new THREE.CanvasTexture(labelCanvas);
	texture.colorSpace = THREE.SRGBColorSpace;
	const label = new THREE.Mesh(
		new THREE.PlaneGeometry(width, width / 2),
		new THREE.MeshBasicMaterial({ map:texture, transparent:true, opacity:0.46, depthWrite:false, side:THREE.DoubleSide })
	);
	label.rotation.set(-Math.PI / 2, 0, Math.PI / 2);
	label.renderOrder = 2;
	label.userData.ignorePicking = true;
	return label;
}

function addDoorMarker(dx, dy, dz) {
	const offset = Math.max(70, Math.min(180, dx * 0.018));
	const frameX = dx + 3;
	const frame = [
		frameX, 0, 0, frameX, dz, 0,
		frameX, dz, 0, frameX, dz, dy,
		frameX, dz, dy, frameX, 0, dy,
		frameX, 0, dy, frameX, 0, 0
	];
	const frameGeometry = new THREE.BufferGeometry();
	frameGeometry.setAttribute("position", new THREE.Float32BufferAttribute(frame, 3));
	const frameLines = new THREE.LineSegments(frameGeometry, new THREE.LineBasicMaterial({ color:doorColor, transparent:true, opacity:0.9 }));
	frameLines.userData.ignorePicking = true;
	state.root.add(frameLines);

	const arrowLength = Math.max(220, Math.min(520, dx * 0.045));
	const arrow = new THREE.ArrowHelper(
		new THREE.Vector3(1, 0, 0),
		new THREE.Vector3(dx + offset, Math.max(12, dz * 0.035), dy / 2),
		arrowLength,
		doorColor.getHex(),
		Math.min(110, arrowLength * 0.28),
		Math.min(58, arrowLength * 0.16)
	);
	arrow.userData.ignorePicking = true;
	state.root.add(arrow);

	const labelWidth = Math.max(1100, Math.min(1500, dy * 0.55));
	const label = createGroundLabel("柜门", labelWidth);
	label.position.set(dx + offset + arrowLength + labelWidth / 4, 1, dy / 2);
	state.root.add(label);

	const tailX = -3;
	const tailFrameGeometry = new THREE.BufferGeometry();
	tailFrameGeometry.setAttribute("position", new THREE.Float32BufferAttribute([
		tailX, 0, 0, tailX, dz, 0,
		tailX, dz, 0, tailX, dz, dy,
		tailX, dz, dy, tailX, 0, dy,
		tailX, 0, dy, tailX, 0, 0
	], 3));
	const tailFrame = new THREE.LineSegments(tailFrameGeometry, new THREE.LineBasicMaterial({ color:0x64748b, transparent:true, opacity:0.62 }));
	tailFrame.userData.ignorePicking = true;
	state.root.add(tailFrame);

	const tailLabel = createGroundLabel("柜尾", labelWidth);
	tailLabel.position.set(-offset - labelWidth / 4, 1, dy / 2);
	state.root.add(tailLabel);
}

function addEdge(positions, a, b) {
	positions.push(a[0], a[1], a[2], b[0], b[1], b[2]);
}

function appendBoxEdges(positions, placement) {
	const item = placement.stackable || {};
	const x0 = Number(placement.x) || 0;
	const y0 = Number(placement.z) || 0;
	const z0 = Number(placement.y) || 0;
	const x1 = x0 + (Number(item.dx) || 0);
	const y1 = y0 + (Number(item.dz) || 0);
	const z1 = z0 + (Number(item.dy) || 0);
	const p = [
		[x0,y0,z0], [x1,y0,z0], [x1,y1,z0], [x0,y1,z0],
		[x0,y0,z1], [x1,y0,z1], [x1,y1,z1], [x0,y1,z1]
	];
	for (const [a,b] of [[0,1],[1,2],[2,3],[3,0],[4,5],[5,6],[6,7],[7,4],[0,4],[1,5],[2,6],[3,7]]) addEdge(positions, p[a], p[b]);
}

function buildContainer(container) {
	disposeRoot();
	state.root = new THREE.Group();
	scene.add(state.root);

	const dx = Number(container.loadDx) || Number(container.dx) || 0;
	const dy = Number(container.loadDy) || Number(container.dy) || 0;
	const dz = Number(container.loadDz) || Number(container.dz) || 0;
	addGround(dx, dy, dz);
	const containerGeometry = new THREE.EdgesGeometry(new THREE.BoxGeometry(dx, dz, dy));
	const containerLines = new THREE.LineSegments(containerGeometry, new THREE.LineBasicMaterial({ color:0x8796a2, transparent:true, opacity:0.72 }));
	containerLines.position.set(dx / 2, dz / 2, dy / 2);
	containerLines.userData.ignorePicking = true;
	state.root.add(containerLines);
	addDoorMarker(dx, dy, dz);

	const grouped = new Map();
	for (const placement of container.stack?.placements || []) {
		const house = houseForPlacement(placement);
		if (!grouped.has(house)) grouped.set(house, []);
		grouped.get(house).push(placement);
	}

	for (const [house, placements] of grouped) {
		const baseColor = new THREE.Color(colorFor(house));
		const material = new THREE.MeshLambertMaterial({ color:0xffffff, polygonOffset:true, polygonOffsetFactor:1, polygonOffsetUnits:1 });
		const mesh = new THREE.InstancedMesh(unitBoxGeometry, material, placements.length);
		mesh.castShadow = true;
		mesh.receiveShadow = true;
		mesh.userData.house = house;
		mesh.userData.placements = placements;
		mesh.userData.baseColor = baseColor;
		const matrix = new THREE.Matrix4();
		const position = new THREE.Vector3();
		const scale = new THREE.Vector3();
		const edgePositions = [];
		placements.forEach((placement, index) => {
			const item = placement.stackable || {};
			const itemDx = Number(item.dx) || 0;
			const itemDy = Number(item.dy) || 0;
			const itemDz = Number(item.dz) || 0;
			position.set((Number(placement.x) || 0) + itemDx / 2, (Number(placement.z) || 0) + itemDz / 2, (Number(placement.y) || 0) + itemDy / 2);
			scale.set(itemDx, itemDz, itemDy);
			matrix.compose(position, identityQuaternion, scale);
			mesh.setMatrixAt(index, matrix);
			mesh.setColorAt(index, baseColor);
			appendBoxEdges(edgePositions, placement);
		});
		mesh.instanceMatrix.needsUpdate = true;
		mesh.instanceColor.needsUpdate = true;
		mesh.computeBoundingSphere();
		state.root.add(mesh);
		state.cargoMeshes.push(mesh);

		const edgeGeometry = new THREE.BufferGeometry();
		edgeGeometry.setAttribute("position", new THREE.Float32BufferAttribute(edgePositions, 3));
		const edgeMaterial = new THREE.LineBasicMaterial({ color:0x53616c, transparent:true, opacity:0.32, depthWrite:false });
		const edges = new THREE.LineSegments(edgeGeometry, edgeMaterial);
		edges.renderOrder = 2;
		edges.userData.ignorePicking = true;
		state.root.add(edges);
		state.cargoGroups.push({ house, placements, mesh, edges, baseColor });
	}

	fitCamera(dx, dy, dz, false);
}

function fitCamera(dx, dy, dz, preserveDirection = false) {
	const rect = canvas.getBoundingClientRect();
	const aspect = Math.max(0.2, rect.width / Math.max(1, rect.height));
	const fittedHeight = Math.max(dz * 2.4, dy * 2.1, dx / aspect * 1.28, 1);
	state.frustumHeight = fittedHeight * 1.58;
	const target = new THREE.Vector3(dx / 2, dz / 2, dy / 2);
	const diagonal = Math.max(1, Math.hypot(dx, dy, dz));
	const currentDirection = camera.position.clone().sub(controls.target);
	const direction = preserveDirection && currentDirection.lengthSq() > 0
		? currentDirection.normalize()
		: new THREE.Vector3(0.9, 0.78, 1.7).normalize();
	camera.position.copy(target).addScaledVector(direction, diagonal * 2.2);
	camera.near = Math.max(0.1, diagonal * 0.001);
	camera.far = diagonal * 8;
	camera.zoom = 1;
	controls.target.copy(target);
	controls.update();
	updateCameraProjection();
}

function updateCameraProjection() {
	const rect = canvas.getBoundingClientRect();
	const aspect = Math.max(0.2, rect.width / Math.max(1, rect.height));
	camera.left = -state.frustumHeight * aspect / 2;
	camera.right = state.frustumHeight * aspect / 2;
	camera.top = state.frustumHeight / 2;
	camera.bottom = -state.frustumHeight / 2;
	camera.updateProjectionMatrix();
}

function resize() {
	if (!renderer || !camera) return;
	const rect = canvas.getBoundingClientRect();
	renderer.setSize(Math.max(1, Math.round(rect.width)), Math.max(1, Math.round(rect.height)), false);
	updateCameraProjection();
}

function applyVisualState() {
	for (const group of state.cargoGroups) {
		group.placements.forEach((placement, index) => {
			let color = group.baseColor;
			if (state.selectedHouse && group.house !== state.selectedHouse) color = group.baseColor.clone().lerp(dimColor, 0.76);
			else if (state.selectedHouse === group.house) color = group.baseColor.clone().lerp(new THREE.Color(0xffffff), 0.08);
			group.mesh.setColorAt(index, color);
		});
		group.mesh.instanceColor.needsUpdate = true;
		group.edges.material.opacity = state.selectedHouse ? (group.house === state.selectedHouse ? 0.7 : 0.08) : 0.32;
		group.edges.material.color.set(state.selectedHouse === group.house ? 0x40515d : 0x53616c);
	}
	updateSelectionOutline();
}

function updateSelectionOutline() {
	if (state.selectionOutline) {
		state.root?.remove(state.selectionOutline);
		state.selectionOutline.traverse(child => {
			child.geometry?.dispose();
			child.material?.dispose();
		});
		state.selectionOutline = null;
	}
	if (!state.selectedPlacement || !state.root) return;
	const group = state.cargoGroups.find(candidate => candidate.placements.includes(state.selectedPlacement));
	if (!group) return;
	const index = group.placements.indexOf(state.selectedPlacement);
	const matrix = new THREE.Matrix4();
	const position = new THREE.Vector3();
	const quaternion = new THREE.Quaternion();
	const scale = new THREE.Vector3();
	group.mesh.getMatrixAt(index, matrix);
	matrix.decompose(position, quaternion, scale);
	const outlineGroup = new THREE.Group();
	const addOutline = (color, scaleFactor, opacity, renderOrder) => {
		const outlineScale = scale.clone().multiplyScalar(scaleFactor);
		const outlineMatrix = new THREE.Matrix4().compose(position, quaternion, outlineScale);
		const outline = new THREE.LineSegments(
			new THREE.EdgesGeometry(unitBoxGeometry),
			new THREE.LineBasicMaterial({
				color,
				transparent:true,
				opacity,
				depthTest:true,
				depthWrite:false
			})
		);
		outline.applyMatrix4(outlineMatrix);
		outline.renderOrder = renderOrder;
		outline.userData.ignorePicking = true;
		outlineGroup.add(outline);
	};
	addOutline(selectionBlue, 1.024, 0.98, 20);
	addOutline(selectionWhite, 1.010, 0.92, 21);
	outlineGroup.userData.ignorePicking = true;
	state.root.add(outlineGroup);
	state.selectionOutline = outlineGroup;
}

function setDetailPanelOpen(open) {
	detailPanelEl.hidden = !open;
	if (open) requestAnimationFrame(updateDetailPosition);
}

function selectedPlacementAnchor() {
	if (!state.selectedPlacement) return null;
	const group = state.cargoGroups.find(candidate => candidate.placements.includes(state.selectedPlacement));
	if (!group) return null;
	const index = group.placements.indexOf(state.selectedPlacement);
	const matrix = new THREE.Matrix4();
	group.mesh.getMatrixAt(index, matrix);
	const anchor = new THREE.Vector3(0, 0.56, 0).applyMatrix4(matrix);
	group.mesh.localToWorld(anchor);
	return anchor;
}

function updateDetailPosition() {
	if (detailPanelEl.hidden || !state.selectedPlacement || !camera) return;
	const anchor = selectedPlacementAnchor();
	if (!anchor) return;
	const projected = anchor.clone().project(camera);
	const rect = canvas.getBoundingClientRect();
	if (projected.z < -1 || projected.z > 1) {
		detailPanelEl.style.visibility = "hidden";
		return;
	}
	detailPanelEl.style.visibility = "visible";
	const anchorX = rect.left + (projected.x + 1) * rect.width / 2;
	const anchorY = rect.top + (1 - projected.y) * rect.height / 2;
	const cardWidth = detailPanelEl.offsetWidth;
	const cardHeight = detailPanelEl.offsetHeight;
	const gap = 44;
	const targetLeft = anchorX - cardWidth / 2;
	const targetTop = anchorY - cardHeight - gap;
	detailPanelEl.style.left = `${Math.round(targetLeft)}px`;
	detailPanelEl.style.top = `${Math.round(targetTop)}px`;
}

function showPlacementDetails(placement) {
	state.selectedPlacement = placement;
	if (!placement) {
		setDetailPanelOpen(false);
		applyVisualState();
		return;
	}
	detailBodyEl.replaceChildren();
	addDetailRow("分单号", placement.houseBsNo || placement.houseBsId || "-");
	addDetailRow("进仓编号", placement.inboundNo || placement.inboundId || "-");
	addDetailRow("货物名称", placement.description || "-");
	addDetailRow("尺寸", `${formatCm(placement.originalDx)} × ${formatCm(placement.originalDy)} × ${formatCm(placement.originalDz)}`);
	addDetailRow("摆放坐标", `X ${formatCm(placement.x)} / Y ${formatCm(placement.y)} / Z ${formatCm(placement.z)}`);
	addDetailRow("单件重量", Number.isFinite(Number(placement.unitWeightKg)) ? `${Number(Number(placement.unitWeightKg).toFixed(3))} kg` : "-");
	addDetailRow("单件体积", Number.isFinite(Number(placement.unitVolumeM3)) ? `${Number(Number(placement.unitVolumeM3).toFixed(6))} m³` : "-");
	detailPanelEl.style.setProperty("--detail-color", colorFor(houseForPlacement(placement)));
	setDetailPanelOpen(true);
	applyVisualState();
}

function placementAt(event) {
	const rect = canvas.getBoundingClientRect();
	pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
	pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
	raycaster.setFromCamera(pointer, camera);
	const hit = raycaster.intersectObjects(state.cargoMeshes, false)[0];
	return hit && hit.instanceId !== undefined ? hit.object.userData.placements[hit.instanceId] : null;
}

function pickPlacement(event) {
	showPlacementDetails(placementAt(event));
}

function hideHoverTooltip() {
	state.hoveredPlacement = null;
	hoverTooltipEl.hidden = true;
	canvas.classList.remove("cargo-hover");
}

function updateHoverTooltip(event) {
	if (event.buttons || state.pointerDown) {
		hideHoverTooltip();
		return;
	}
	const placement = placementAt(event);
	if (!placement) {
		hideHoverTooltip();
		return;
	}
	state.hoveredPlacement = placement;
	const item = placement.stackable || {};
	const house = placement.houseBsNo || placement.houseBsId || houseForPlacement(placement);
	const inbound = placement.inboundNo || placement.inboundId || "-";
	hoverTooltipEl.replaceChildren();
	const title = document.createElement("strong");
	title.textContent = `分单 ${house}`;
	const info = document.createElement("span");
	info.textContent = `进仓 ${inbound} · ${formatCm(item.dx)} × ${formatCm(item.dy)} × ${formatCm(item.dz)}`;
	hoverTooltipEl.append(title, info);
	hoverTooltipEl.style.left = `${Math.min(event.clientX, window.innerWidth - 275)}px`;
	hoverTooltipEl.style.top = `${Math.min(event.clientY, window.innerHeight - 75)}px`;
	hoverTooltipEl.hidden = false;
	canvas.classList.add("cargo-hover");
}

function updateContainerInfo(container) {
	const placements = container.stack?.placements || [];
	const volume = placements.reduce((sum, placement) => {
		const item = placement.stackable || {};
		return sum + (Number(item.dx) || 0) * (Number(item.dy) || 0) * (Number(item.dz) || 0);
	}, 0);
	const dx = Number(container.loadDx) || Number(container.dx) || 0;
	const dy = Number(container.loadDy) || Number(container.dy) || 0;
	const dz = Number(container.loadDz) || Number(container.dz) || 0;
	const capacity = Math.max(1, dx * dy * dz);
	const totalWeight = placements.reduce((sum, placement) => sum + (Number(placement.unitWeightKg) || 0), 0);
	const utilization = volume / capacity * 100;
	const percentage = Math.max(0, Math.min(100, utilization));
	const ringColor = utilization >= 80 ? "#4b9d8a" : utilization < 60 ? "#8194a8" : "#4b91cf";
	containerInfoBodyEl.replaceChildren();
	const headerEl = document.createElement("div");
	headerEl.className = "stats-header";
	const titleEl = document.createElement("span");
	titleEl.className = "stats-title";
	titleEl.textContent = "装载统计";
	const typeEl = document.createElement("span");
	typeEl.className = "stats-container-type";
	typeEl.textContent = container.name || container.type || "未知柜型";
	headerEl.append(titleEl, typeEl);
	const contentEl = document.createElement("div");
	contentEl.className = "stats-content";
	const summaryEl = document.createElement("div");
	summaryEl.className = "stats-summary";
	summaryEl.append(
		createStatsItem("货物总数", String(placements.length), "件"),
		createStatsItem("货物体积", (volume / 1_000_000_000).toFixed(2), "m³"),
		createStatsItem("货物总重", String(Number(totalWeight.toFixed(2))), "kg")
	);
	const utilizationEl = document.createElement("div");
	utilizationEl.className = "utilization-block";
	const ringEl = document.createElement("div");
	ringEl.className = "utilization-ring";
	ringEl.style.background = `conic-gradient(${ringColor} ${percentage * 3.6}deg, #e8edf2 0deg)`;
	ringEl.setAttribute("aria-label", `体积利用率 ${utilization.toFixed(1)}%`);
	const ringValueEl = document.createElement("span");
	ringValueEl.className = "utilization-ring-value";
	ringValueEl.textContent = `${utilization.toFixed(1)}%`;
	const ringLabelEl = document.createElement("span");
	ringLabelEl.className = "stats-label";
	ringLabelEl.textContent = "体积利用率";
	ringEl.appendChild(ringValueEl);
	utilizationEl.append(ringEl, ringLabelEl);
	contentEl.append(summaryEl, utilizationEl);
	containerInfoBodyEl.append(headerEl, contentEl);
	containerInfoEl.hidden = false;
}

function renderLegend(placements) {
	const houses = new Map();
	placements.forEach(placement => {
		const house = houseForPlacement(placement);
		if (!house) return;
		if (!houses.has(house)) houses.set(house, []);
		houses.get(house).push(placement);
	});
	legendEl.replaceChildren();
	houseListCountEl.textContent = String(houses.size);
	Array.from(houses.keys()).sort().forEach(house => {
		legendEl.appendChild(createLegendButton(house, house, colorFor(house), houses.get(house)));
	});
}

function createLegendButton(label, house, color, placements) {
	const volume = placements.reduce((sum, placement) => {
		const item = placement.stackable || {};
		return sum + (Number(item.dx) || 0) * (Number(item.dy) || 0) * (Number(item.dz) || 0);
	}, 0) / 1_000_000_000;
	const badge = document.createElement("button");
	badge.type = "button";
	badge.className = "badge";
	badge.dataset.house = house ?? "";
	badge.style.setProperty("--badge-color", color);
	badge.setAttribute("aria-pressed", "false");
	const copy = document.createElement("span");
	copy.className = "badge-copy";
	const labelEl = document.createElement("span");
	labelEl.className = "badge-label";
	labelEl.textContent = label;
	const summary = document.createElement("span");
	summary.className = "badge-summary";
	summary.textContent = `${volume.toFixed(2)} m³`;
	const count = document.createElement("span");
	count.className = "badge-count";
	count.textContent = `${placements.length}件`;
	copy.append(labelEl, summary);
	badge.append(copy, count);
	badge.addEventListener("click", () => {
		const nextHouse = state.selectedHouse === house ? null : house;
		state.selectedPlacement = null;
		setDetailPanelOpen(false);
		state.selectedHouse = nextHouse;
		legendEl.querySelectorAll(".badge").forEach(button => {
			const active = nextHouse !== null && button.dataset.house === nextHouse;
			button.classList.toggle("active", active);
			button.setAttribute("aria-pressed", String(active));
		});
		applyVisualState();
	});
	return badge;
}

function selectContainer(index) {
	const containers = state.data?.containers || [];
	const container = containers[index];
	if (!container) return;
	state.selectedIndex = index;
	state.selectedHouse = null;
	state.selectedPlacement = null;
	setDetailPanelOpen(false);
	hideHoverTooltip();
	containerListEl.querySelectorAll("button").forEach((button, buttonIndex) => button.classList.toggle("active", buttonIndex === index));
	updateContainerInfo(container);
	renderLegend(container.stack?.placements || []);
	buildContainer(container);
	applyVisualState();
}

function renderContainerList(containers) {
	containerListEl.replaceChildren();
	containerListCountEl.textContent = String(containers.length);
	containers.forEach((container, index) => {
		const button = document.createElement("button");
		button.type = "button";
		button.className = "container-button";
		const number = document.createElement("span");
		number.className = "container-index";
		number.textContent = String(index + 1).padStart(2, "0");
		const copy = document.createElement("span");
		copy.className = "container-copy";
		const name = document.createElement("span");
		name.className = "container-name";
		name.textContent = container.id || container.name || "未命名柜";
		const type = document.createElement("span");
		type.className = "container-type";
		type.textContent = container.name || container.type || "未知柜型";
		copy.append(name, type);
		button.append(number, copy);
		button.addEventListener("click", () => selectContainer(index));
		containerListEl.appendChild(button);
	});
}

async function load() {
	const resultId = resultIdFromPath();
	if (!resultId) throw new Error("URL 缺少 resultId");
	const response = await fetch(`/api/packing/results/${encodeURIComponent(resultId)}/visualization`);
	if (!response.ok) throw new Error(`HTTP ${response.status}`);
	state.data = await response.json();
	const containers = state.data.containers || [];
	buildHouseColors(containers);
	const pieceCount = containers.reduce((sum, container) => sum + (container.stack?.placements?.length || 0), 0);
	masterTitleEl.textContent = `主单：${state.data.masterNo || state.data.masterBsId || "未提供主单号"}`;
	statusEl.textContent = `结果编号 ${resultId}`;
	overallContainerCountEl.textContent = containers.length;
	overallPieceCountEl.textContent = pieceCount;
	renderContainerList(containers);
	selectContainer(0);
}

canvas.addEventListener("pointerdown", event => {
	hideHoverTooltip();
	state.pointerDown = { x:event.clientX, y:event.clientY, button:event.button };
});
canvas.addEventListener("pointerup", event => {
	const down = state.pointerDown;
	state.pointerDown = null;
	if (!down || down.button !== 0 || Math.hypot(event.clientX - down.x, event.clientY - down.y) > 5) return;
	pickPlacement(event);
});
canvas.addEventListener("pointermove", updateHoverTooltip);
canvas.addEventListener("pointerleave", hideHoverTooltip);
canvas.addEventListener("contextmenu", event => event.preventDefault());
window.addEventListener("resize", resize);

try {
	initializeThree();
	await load();
} catch (error) {
	statusEl.classList.add("error");
	statusEl.textContent = `加载失败：${error.message}`;
	console.error(error);
}
