import qupath.lib.objects.PathRootObject

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
resolveHierarchy()

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

// ---- 3. Shape features ----
selectDetections()
addShapeMeasurements("AREA", "LENGTH", "CIRCULARITY", "SOLIDITY", "MAX_DIAMETER", "MIN_DIAMETER")

// ---- 4. Intensity features (annotations selected = parent scope) ----
selectAnnotations()
runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"NUCLEUS","tileSizeMicrons":25.0,"colorOD":false,"colorStain1":true,"colorStain2":true,"colorStain3":false,"colorRed":false,"colorGreen":false,"colorBlue":false,"colorHue":false,"colorSaturation":false,"colorBrightness":false,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":false,"haralickDistance":1,"haralickBins":32}')

selectAnnotations()
runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"SQUARE","tileSizeMicrons":25.0,"colorOD":false,"colorStain1":true,"colorStain2":true,"colorStain3":false,"colorRed":false,"colorGreen":false,"colorBlue":false,"colorHue":false,"colorSaturation":false,"colorBrightness":false,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":false,"haralickDistance":1,"haralickBins":32}')

// ---- 5. Fix Std.dev. -> Std.Dev. naming BEFORE smoothing (so smoothed names inherit it) ----
int renamed = 0
for (d in getDetectionObjects()) {
    def ml = d.getMeasurementList()
    def toFix = ml.getMeasurementNames().findAll { it.contains("Std.dev.") }
    for (old in toFix) {
        ml.put(old.replace("Std.dev.", "Std.Dev."), ml.get(old))
    }
    ml.close()
    renamed++
}
println "${imageName}: renamed Std.dev. on ${renamed} detections"

// ---- 6. Smoothing (annotations selected = parent scope) ----
// Snapshot count before, so we can verify smoothing actually added features
def before = getDetectionObjects()[0].getMeasurementList().getMeasurementNames().size()
long t0 = System.currentTimeMillis()

selectAnnotations()
runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons":50.0,"smoothWithinClasses":false}')
selectAnnotations()
runPlugin('qupath.lib.plugins.objects.SmoothFeaturesPlugin', '{"fwhmMicrons":75.0,"smoothWithinClasses":false}')

long elapsed = System.currentTimeMillis() - t0

// ---- 6b. Smoothing verification ----
def names = getDetectionObjects()[0].getMeasurementList().getMeasurementNames()
def after = names.size()
def has50 = names.any { it.startsWith("Smoothed: 50") }
def has75 = names.any { it.startsWith("Smoothed: 75") }
def hasNearby = names.any { it.contains("Nearby detection counts") }
def smoothingOk = has50 && has75 && hasNearby && (after > before)

if (!smoothingOk) {
    println "!!!! SMOOTHING FAILED on ${imageName} — features NOT added (before=${before}, after=${after}, " +
            "s50=${has50}, s75=${has75}, nearby=${hasNearby}, elapsed=${elapsed}ms). " +
            "Detections likely lack a valid annotation parent. NOT saving."
    return   // don't persist a broken image
}
if (elapsed < 2000) {
    println "!!!! WARNING on ${imageName}: smoothing finished in ${elapsed}ms — suspiciously fast for ${remaining} detections. " +
            "Verify features are real before trusting this image."
}
println "${imageName}: smoothing OK (${before} -> ${after} features in ${elapsed}ms)"

// ---- 7. Save (only reached if smoothing verified) ----
fireHierarchyUpdate()
getProjectEntry()?.saveImageData(getCurrentImageData())

// ---- 8. Per-image sanity report ----
println "${imageName}: DONE — ${after} measurements | smoothed50=${has50} smoothed75=${has75} StdDev=${names.count{it.contains('Std.Dev.')}}"