import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.images.writers.TileExporter
import qupath.lib.common.GeneralTools
import qupath.lib.common.ColorTools

def imageData = getCurrentImageData()
if (imageData == null) {
    print "No image open!"
    return
}

def hierarchy = imageData.getHierarchy()
def server = imageData.getServer()

// ---------------- OUTPUT DIR ----------------
def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
def baseOutput = buildFilePath(PROJECT_BASE_DIR, 'tiles_tumor_strict', name)
mkdirs(baseOutput)

def imagesSubDir = 'images'
def masksSubDir  = 'masks'

// ---------------- BASIC RESOLUTION ------------
double pixelSize = server.getPixelCalibration().getAveragedPixelSize()
double downsample = 1.0   // full resolution

// ---------------- 1) FIND TUMOR ANNOTATIONS ------
def tumorAnnotations = hierarchy.getAnnotationObjects().findAll {
    it.getPathClass()?.toString() == "Tumor"
}

if (tumorAnnotations.isEmpty()) {
    print "No Tumor annotations found!"
    return
}

print "Tumor annotations: " + tumorAnnotations.size()

// ---------------- 2) FIND ALL CELLS ---------------
def allCells = hierarchy.getDetectionObjects()
print "Total detections: " + allCells.size()

// ---------------- 3) MARK ONLY CELLS INSIDE TUMOR ANNOTATIONS -----------------
def tumorCells = []
allCells.each { cell ->
    def roi = cell.getROI()
    def cx = roi.getCentroidX()
    def cy = roi.getCentroidY()
    
    // if centroid lies in any annotation
    def inside = tumorAnnotations.any { ann -> ann.getROI().contains(cx, cy) }

    if (inside) {
        cell.setPathClass(getPathClass("Tumor"))
        tumorCells << cell
    } else {
        cell.setPathClass(getPathClass("Other"))
    }
}

print "Tumor cells found inside annotations: " + tumorCells.size()

// ---------------- 4) BUILD LABEL SERVER ----------------
def labelServer = new LabeledImageServer.Builder(imageData)
    .useDetections()                     // <-- use detection objects (cells)
    .backgroundLabel(0, ColorTools.BLACK)
    .downsample(downsample)
    .addLabel("Tumor", 1)                // ONLY Tumor cells become 1
    .multichannelOutput(false)
    .build()

// ---------------- 5) EXPORT TILES -------------------
new TileExporter(imageData)
    .downsample(downsample)
    .tileSize(512)
    .overlap(64)
    .parentObjects(tumorAnnotations)      // tiles around tumor annotations only
    .imageExtension(".png")
    .imageSubDir(imagesSubDir)
    .labeledServer(labelServer)
    .labeledImageExtension(".png")
    .labeledImageSubDir(masksSubDir)
    .labeledImageId("_mask")
    .writeTiles(baseOutput)

print "Done! Strict tumor masks written to: " + baseOutput
