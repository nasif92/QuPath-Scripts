import qupath.lib.objects.PathRootObject
import qupath.lib.gui.prefs.PathPrefs

// ---- Guard: skip images without annotations (would otherwise delete all detections) ----
def imageName = getProjectEntry()?.getImageName() ?: "unknown"
if (getAnnotationObjects().isEmpty()) {
    println "WARNING: no annotations on ${imageName} — skipping to avoid wiping detections."
    return
}
if (getDetectionObjects().isEmpty()) {
    println "No detections on ${imageName} — skipping."
    return
}

// ---- Stain setup (must match training) ----
setImageType('BRIGHTFIELD_H_DAB')
setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.6511078257574492 0.7011930431234068 0.29049426072255424", "Stain 2" : "DAB", "Values 2" : "0.2691668720495607 0.5682411743268503 0.7775931859209531", "Background" : " 255 255 255"}')

// ---- 1. Re-parent detections into containing annotations ----
//resolveHierarchy()

// ---- 2. Remove detections outside all tissue annotations (parented to root) ----
def orphans = getDetectionObjects().findAll { it.getParent() instanceof PathRootObject }
println "${imageName}: removing ${orphans.size()} detections outside tissue"
removeObjects(orphans, true)
def remaining = getDetectionObjects().size()
println "${imageName}: ${remaining} detections remain"
if (remaining == 0) {
    println "WARNING: no detections left on ${imageName} after orphan removal — skipping features."
    return
}

// ---- 3. Shape features (NUCLEUS_CELL_RATIO added to match the classifier's training set) ----
selectDetections()
addShapeMeasurements("AREA", "LENGTH", "CIRCULARITY", "SOLIDITY", "MAX_DIAMETER", "MIN_DIAMETER", "NUCLEUS_CELL_RATIO")

// ---- 4. Intensity features — DETECTIONS selected on purpose:
//         IntensityFeaturesPlugin measures the SELECTED objects directly (it does not cascade
//         to child detections), and region:NUCLEUS only resolves a ROI on cell objects. ----
selectDetections()
runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"NUCLEUS","tileSizeMicrons":25.0,"colorOD":false,"colorStain1":true,"colorStain2":true,"colorStain3":false,"colorRed":false,"colorGreen":false,"colorBlue":false,"colorHue":false,"colorSaturation":false,"colorBrightness":false,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":false,"haralickDistance":1,"haralickBins":32}')

selectDetections()
runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"SQUARE","tileSizeMicrons":25.0,"colorOD":false,"colorStain1":true,"colorStain2":true,"colorStain3":false,"colorRed":false,"colorGreen":false,"colorBlue":false,"colorHue":false,"colorSaturation":false,"colorBrightness":false,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":false,"haralickDistance":1,"haralickBins":32}')

// ---- 4b. Fail loudly if intensity features were NOT added to detections ----
//      ("per pixel" appears only in IntensityFeaturesPlugin names, never in shape/smoothed-shape) ----
def intensityCheck = getDetectionObjects()[0].getMeasurementList().getMeasurementNames().findAll { it.contains("per pixel") }
if (intensityCheck.isEmpty()) {
    println "!!!! INTENSITY FAILED on ${imageName} — no per-cell intensity features on detections. " +
            "Check that selectDetections() ran and detections are cells with a nucleus ROI. NOT saving."
    return
}
println "${imageName}: intensity OK (${intensityCheck.size()} intensity features on first detection)"

// ---- 5. (Std.dev. -> Std.Dev. rename intentionally removed: the classifier was trained on the
//         plugin's native lowercase names. Re-add ONLY if it was retrained on capitalized names.) ----

// ---- 6. Smoothing (single-threaded to avoid ConcurrentModificationException on MeasurementList) ----
def originalThreads = PathPrefs.numCommandThreadsProperty().get()
PathPrefs.numCommandThreadsProperty().set(1)

def before = getDetectionObjects()[0].getMeasurementList().getMeasurementNames().size()
long t0 = System.currentTimeMillis()
long elapsed

try {
    selectAnnotations()
    runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons":50.0,"smoothWithinClasses":false}')
    selectAnnotations()
    runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons":75.0,"smoothWithinClasses":false}')

    elapsed = System.currentTimeMillis() - t0
} finally {
    PathPrefs.numCommandThreadsProperty().set(originalThreads)
}

// ---- 6b. Smoothing verification (now also requires intensity features to be present) ----
def names = getDetectionObjects()[0].getMeasurementList().getMeasurementNames()
def after = names.size()
def has50 = names.any { it.startsWith("Smoothed: 50") }
def has75 = names.any { it.startsWith("Smoothed: 75") }
def hasNearby = names.any { it.contains("Nearby detection counts") }
def hasIntensity = names.any { it.contains("per pixel") }
def smoothingOk = has50 && has75 && hasNearby && hasIntensity && (after > before)

if (!smoothingOk) {
    println "!!!! SMOOTHING FAILED on ${imageName} — features NOT added (before=${before}, after=${after}, " +
            "s50=${has50}, s75=${has75}, nearby=${hasNearby}, intensity=${hasIntensity}, elapsed=${elapsed}ms). " +
            "Detections likely lack a valid annotation parent. NOT saving."
    return   // don't persist a broken image
}
if (elapsed < 2000) {
    println "!!!! WARNING on ${imageName}: smoothing finished in ${elapsed}ms — suspiciously fast for ${remaining} detections. " +
            "Verify features are real before trusting this image."
}
println "${imageName}: smoothing OK (${before} -> ${after} features in ${elapsed}ms)"

// ---- 7. Save (only reached if intensity + smoothing verified) ----
fireHierarchyUpdate()
getProjectEntry()?.saveImageData(getCurrentImageData())

// ---- 8. Per-image sanity report ----
println "${imageName}: DONE — ${after} measurements | intensity=${hasIntensity} smoothed50=${has50} smoothed75=${has75}"
