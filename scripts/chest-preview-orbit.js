// Inserted into the existing atelier player by render-chest-preview.py.
const orbitPlay = document.getElementById('orbitPlay');
const orbitTime = document.getElementById('orbitTime');
const oldAnchor = document.getElementById('oldAnchor');
const orbitCamera = new THREE.PerspectiveCamera();
const initialRotationInverse = new THREE.Quaternion();
const panelRotation = new THREE.Quaternion();
const panelCenter = new THREE.Vector3();
const endPoint = new THREE.Vector3();
let orbitState = null, orbitSeconds = 0, lastPaint = 0, orbitPlaying = true;

function setPlaying(playing) {
  orbitPlaying = playing;
  orbitPlay.textContent = playing ? 'Пауза' : 'Продолжить';
}
orbitPlay.addEventListener('click', () => setPlaying(!orbitPlaying));
orbitTime.addEventListener('input', () => {
  setPlaying(false);
  orbitSeconds = Number(orbitTime.value);
});

function updateOrbit() {
  const moving = !!currentState?.orbit;
  const firstPerson = document.querySelector('[data-view="player"]').getAttribute('aria-pressed') === 'true';
  document.getElementById('orbitControls').hidden = !moving;
  document.getElementById('crosshair').hidden = !firstPerson;
  controls.enabled = !(moving && firstPerson);
  if (!moving) {
    orbitState = null;
    controls.update();
    return;
  }
  const now = performance.now() / 1000;
  const samples = PREVIEW_SCENE.orbit;
  const period = samples.length - 1; // final camera matches the start
  const aim = metadataPoint(currentState.camera.target);
  if (orbitState !== currentState.id) {
    orbitState = currentState.id;
    orbitSeconds = 0;
    lastPaint = now;
    orbitCamera.position.fromArray(samples[0].camera);
    orbitCamera.lookAt(aim);
    initialRotationInverse.copy(orbitCamera.quaternion).invert();
  }
  if (orbitPlaying) orbitSeconds = (orbitSeconds + now - lastPaint) % (period / 20);
  lastPaint = now;
  orbitTime.value = orbitSeconds;
  const time = orbitSeconds * 20 % period;
  const tick = Math.floor(time);
  orbitCamera.position.fromArray(samples[tick].camera)
    .lerp(endPoint.fromArray(samples[tick + 1].camera), time - tick);
  orbitCamera.lookAt(aim);
  if (firstPerson) {
    camera.position.copy(orbitCamera.position);
    camera.quaternion.copy(orbitCamera.quaternion);
  } else controls.update();

  // The Kotlin export already applies the real deadband and cadence. Model a client
  // reaching each admitted target after its configured teleport interpolation window.
  const duration = PREVIEW_SCENE.motionTicks;
  const update = tick - tick % duration;
  const start = (update - duration + period) % period;
  if (oldAnchor.checked) panelCenter.fromArray(samples[0].anchor);
  else panelCenter.fromArray(samples[start].anchor)
    .lerp(endPoint.fromArray(samples[update].anchor), (time - update) / duration);
  panelRotation.copy(orbitCamera.quaternion).multiply(initialRotationInverse);
  for (const piece of work.children) if (piece.userData.panel) {
    piece.position.copy(panelCenter);
    piece.quaternion.copy(panelRotation);
  }
}
