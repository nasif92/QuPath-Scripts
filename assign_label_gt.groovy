import qupath.lib.common.GeneralTools

// ============================================================
// Assigns each cell the class of the ground-truth ANNOTATION that
// contains its centroid. This reconstructs per-cell ground-truth
// labels from Dr. Gilbert's region annotations.
//
// Cells not inside any ground-truth annotation are left unclassified
// (so they're excluded from training/eval rather than mislabeled).
//
// Run via: Automate > Run for project
// ============================================================

// ---------------- SETTINGS ----------------
def gtRegionClasses = [
    "Tumor", "Stroma", "Immune cells",
    "Normal-GI-Mucosa", "Normal-foveola", "Normal-Squamous", "Normal-Brunner",
    "Normal-endometrial-Ep", "Normal-Glands", "Normal-Smooth-Muscle",
    "Necrosis", "Other"
] as Set

boolean CLEAR_CELLS_OUTSIDE = true   // true = wipe class on cells outside any GT annotation

def imageData = getCurrentImageData()
if (imageData == null) { print "No image open!"; return }
def hierarchy = imageData.getHierarchy()
def server = imageData.getServer()
def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
print "=== " + name + " ==="

// This project's objects are plain PathDetectionObjects (e.g. StarDist
// nucleus segmentation), not QuPath "cell" objects - so getCellObjects()
// returns nothing and getDetectionObjects() is what we need.
def cells = getDetectionObjects()
if (cells.isEmpty()) {
    print "No detection objects - run nucleus/cell detection first."
    return
}
print "Detections: " + cells.size()

def gtAnnotations = hierarchy.getAnnotationObjects().findAll { ann ->
    def nm = ann.getPathClass()?.getName()?.trim()
    nm != null && gtRegionClasses.contains(nm)
}
if (gtAnnotations.isEmpty()) { print "No ground-truth annotations found - skipping."; return }
print "Ground-truth annotations: " + gtAnnotations.size()

// Precompute area + bbox. Sorting hits by area means a cell inside nested
// annotations takes the SMALLEST (most specific) one - so a detail annotation
// drawn inside a larger region wins, which is almost certainly the intent.
def annotationArea = gtAnnotations.collectEntries { ann -> [(ann): ann.getROI().getArea()] }
def annotationBounds = gtAnnotations.collectEntries { ann ->
    def r = ann.getROI()
    [(ann): [r.getBoundsX(), r.getBoundsY(), r.getBoundsX()+r.getBoundsWidth(), r.getBoundsY()+r.getBoundsHeight()]]
}

int nAssigned = 0, nOutside = 0
def classCounts = [:].withDefault { 0 }

cells.each { cell ->
    double cx = cell.getROI().getCentroidX(); double cy = cell.getROI().getCentroidY()
    def hits = []
    for (ann in gtAnnotations) {
        def b = annotationBounds[ann]
        if (cx < b[0] || cx > b[2] || cy < b[1] || cy > b[3]) continue   // cheap bbox reject first
        if (ann.getROI().contains(cx, cy)) hits << ann
    }
    if (hits.isEmpty()) {
        if (CLEAR_CELLS_OUTSIDE) cell.setPathClass(null)
        nOutside++
    } else {
        def best = hits.min { annotationArea[it] }   // most specific containing annotation
        def pc = best.getPathClass()
        cell.setPathClass(pc)
        classCounts[pc.getName()]++
        nAssigned++
    }
}
fireHierarchyUpdate()

print "Assigned from annotations : " + nAssigned
print "Outside any annotation    : " + nOutside + (CLEAR_CELLS_OUTSIDE ? " (cleared)" : " (left as-is)")
print "Per-class counts:"
classCounts.sort { -it.value }.each { k, v -> print "  " + k + ": " + v }

// Persist so the labels survive for the measurement export step.
try {
    def entry = getProjectEntry()
    if (entry != null) { entry.saveImageData(imageData); print "Saved image data." }
} catch (Exception e) {
    print "Could not auto-save (" + e.getMessage() + ") - save the project manually before exporting."
}
