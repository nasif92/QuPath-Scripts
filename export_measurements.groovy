import qupath.lib.common.GeneralTools

// ============================================================
// Exports per-nucleus MEASUREMENTS ONLY (no tiles, no masks) for
// detections inside Dr. Gilbert's ground-truth annotations.
//
// Matched to THIS project's actual measurement names: plain
// PathDetectionObjects (StarDist-style nucleus segmentation) with
// "Square: Diameter 25.0 µm" intensity features, NOT QuPath cell
// detection objects. There is no cell/cytoplasm/membrane boundary,
// so no Cell:/Cytoplasm:/Membrane: measurements exist.
//
// Run AFTER assign_cells_from_annotations.groovy (which restores the
// per-detection classes from the annotation regions).
// Run via: Automate > Run for project
// ============================================================

// ---------------- SETTINGS ----------------
def gtRegionClasses = [
    "Tumor", "Stroma", "Immune cells",
    "Normal-GI-Mucosa", "Normal-foveola", "Normal-Squamous", "Normal-Brunner",
    "Normal-endometrial-Ep", "Normal-Glands", "Normal-Smooth-Muscle",
    "Necrosis", "Other"
] as Set
def keepClasses = gtRegionClasses

boolean INSIDE_ANNOTATIONS_ONLY = true

// Optional size filter - the area distribution has a long tail at both ends
// (tiny fragments/debris, and large merged clumps). Set to 0/Double.MAX_VALUE
// to disable. Median area on this data is ~18 um^2.
double MIN_AREA_UM2 = 0.0
double MAX_AREA_UM2 = Double.MAX_VALUE

def imageData = getCurrentImageData()
if (imageData == null) { print "No image open!"; return }
def hierarchy = imageData.getHierarchy()
def server = imageData.getServer()
def imageName = server.getMetadata().getName()
if (imageName.toUpperCase().contains("HE")) { print "Skipping H&E image: " + imageName; return }
def name = GeneralTools.getNameWithoutExtension(imageName)
print "=== " + name + " ==="

// ---------------- MEASUREMENT NAMES ----------------
// Base shape features.
def SHAPE = ["Area µm^2", "Length µm", "Circularity", "Solidity",
             "Max diameter µm", "Min diameter µm"]

// Intensity features. NOTE: this project has BOTH "Std.dev." and "Std.Dev."
// variants with identical values (duplicate from running the feature step
// under two QuPath versions) - only the lowercase one is exported.
def SQ = "Square: Diameter 25.0 µm: 2.00 µm per pixel: "
def INTENSITY = [
    SQ + "Hematoxylin: Mean", SQ + "Hematoxylin: Std.dev.", SQ + "Hematoxylin: Min",
    SQ + "Hematoxylin: Max",  SQ + "Hematoxylin: Median",
    SQ + "DAB: Mean",         SQ + "DAB: Std.dev.",         SQ + "DAB: Min",
    SQ + "DAB: Max",          SQ + "DAB: Median"
]

def baseFeatures = SHAPE + INTENSITY
// Smoothed variants exist for every base feature, plus a neighbour count.
def smoothed50 = baseFeatures.collect { "Smoothed: 50 µm: " + it } + ["Smoothed: 50 µm: Nearby detection counts"]
def smoothed75 = baseFeatures.collect { "Smoothed: 75 µm: " + it } + ["Smoothed: 75 µm: Nearby detection counts"]
def allFeatures = baseFeatures + smoothed50 + smoothed75

// Short, CSV-friendly column names (Python-safe: no spaces/colons/unicode).
def shortName = { String m ->
    String s = m
    s = s.replace(SQ, "sq25_")
    s = s.replace("Smoothed: 50 µm: ", "sm50_")
    s = s.replace("Smoothed: 75 µm: ", "sm75_")
    s = s.replace("Area µm^2", "area_um2").replace("Length µm", "length_um")
    s = s.replace("Max diameter µm", "max_diam_um").replace("Min diameter µm", "min_diam_um")
    s = s.replace("Nearby detection counts", "nearby_count")
    s = s.replace("Hematoxylin", "hem").replace("DAB", "dab")
    s = s.replace("Std.dev.", "std").replace("Std.Dev.", "std")
    s = s.replace("Circularity", "circularity").replace("Solidity", "solidity")
    s = s.replace("Mean", "mean").replace("Median", "median")
    s = s.replace("Min", "min").replace("Max", "max")
    s = s.replace(": ", "_").replace(":", "_").replace(" ", "_")
    while (s.contains("__")) s = s.replace("__", "_")
    return s.toLowerCase()
}

// ---------------- GT ANNOTATIONS ----------------
def gtAnnotations = hierarchy.getAnnotationObjects().findAll { ann ->
    def nm = ann.getPathClass()?.getName()?.trim()
    nm != null && gtRegionClasses.contains(nm)
}
if (INSIDE_ANNOTATIONS_ONLY && gtAnnotations.isEmpty()) {
    print "No ground-truth annotations - skipping."; return
}
print "Ground-truth annotations: " + gtAnnotations.size()

def annotationIndex = [:]
gtAnnotations.eachWithIndex { ann, i -> annotationIndex[ann] = i }
def annotationArea = gtAnnotations.collectEntries { ann -> [(ann): ann.getROI().getArea()] }
def annotationBounds = gtAnnotations.collectEntries { ann ->
    def r = ann.getROI()
    [(ann): [r.getBoundsX(), r.getBoundsY(), r.getBoundsX()+r.getBoundsWidth(), r.getBoundsY()+r.getBoundsHeight()]]
}
def containingAnnotations = { det ->
    double cx = det.getROI().getCentroidX(); double cy = det.getROI().getCentroidY()
    def hits = []
    for (ann in gtAnnotations) {
        def b = annotationBounds[ann]
        if (cx < b[0] || cx > b[2] || cy < b[1] || cy > b[3]) continue
        if (ann.getROI().contains(cx, cy)) hits << ann
    }
    return hits.sort { a, b -> annotationArea[a] <=> annotationArea[b] }  // most specific first
}

// ---------------- COLLECT DETECTIONS ----------------
def getMeasure = { det, String mname ->
    try { def v = det.getMeasurementList().get(mname); return v } catch (Exception e) { return Double.NaN }
}

def classified = getDetectionObjects().findAll { det ->
    def cls = det.getPathClass()?.getName()?.trim()
    if (cls == null || !keepClasses.contains(cls)) return false
    double a = getMeasure(det, "Area µm^2")
    return !(a < MIN_AREA_UM2 || a > MAX_AREA_UM2)
}
print "Classified detections (after size filter): " + classified.size()
if (classified.isEmpty()) {
    print "None - did you run assign_cells_from_annotations.groovy first?"
    return
}

def detHits = [:]
classified.each { det -> detHits[det] = containingAnnotations(det) }
def toExport = INSIDE_ANNOTATIONS_ONLY ? classified.findAll { !detHits[it].isEmpty() } : classified
print "Detections to export: " + toExport.size()
if (toExport.isEmpty()) { print "Nothing to export."; return }

def classCounts = [:].withDefault { 0 }
toExport.each { classCounts[it.getPathClass().getName()]++ }
classCounts.sort { -it.value }.each { k, v -> print "  " + k + ": " + v }

// ---------------- WRITE CSV ----------------
def fmt = { val -> (val == null || (val instanceof Double && val.isNaN())) ? "" : String.format("%.6f", val) }

def outDir = buildFilePath(PROJECT_BASE_DIR, 'ground_truth_measurements')
mkdirs(outDir)
def csvFile = new File(buildFilePath(outDir, name + "_gt_measurements.csv"))

int nWritten = 0
csvFile.withWriter { writer ->
    writer.writeLine((["wsi_name","nucleus_id","cx_wsi","cy_wsi",
                       "label","is_ground_truth",
                       "containing_annotation_ids","containing_annotation_classes",
                       "annotation_nesting_depth"] + allFeatures.collect { shortName(it) }).join(","))
    int nucleusId = 0
    toExport.each { det ->
        def roi = det.getROI()
        def hits = detHits[det]
        def row = [name, nucleusId++,
                   fmt(roi.getCentroidX()), fmt(roi.getCentroidY()),
                   det.getPathClass().getName(),
                   (hits.isEmpty() ? "false" : "true"),
                   hits.collect { annotationIndex[it] }.join("|"),
                   hits.collect { it.getPathClass()?.getName() ?: "" }.join("|"),
                   hits.size()]
        allFeatures.each { m -> row << fmt(getMeasure(det, m)) }
        writer.writeLine(row.join(","))
        nWritten++
    }
}
print "Wrote " + nWritten + " nuclei x " + allFeatures.size() + " features -> " + csvFile.getPath()
