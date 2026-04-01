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

// ---------------- SETTINGS ----------------
def tileSize = 512
def overlap = 64
double downsample = 1.0   // full resolution

def classNames = ["Tumor", "Stroma", "Other"]

// ---------------- OUTPUT DIR ----------------
def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
def baseOutput = buildFilePath(PROJECT_BASE_DIR, 'tiles_multiclass_regions', name)
mkdirs(baseOutput)

def imagesSubDir = 'images'
def masksSubDir  = 'masks'

// ---------------- FIND ANNOTATIONS ----------------
def regionAnnotations = hierarchy.getAnnotationObjects().findAll { ann ->
    def pc = ann.getPathClass()
    pc != null && classNames.contains(pc.toString())
}

if (regionAnnotations.isEmpty()) {
    print "No Tumor/Stroma/Other annotations found!"
    return
}

print "Found region annotations: " + regionAnnotations.size()

def tumorCount  = regionAnnotations.count { it.getPathClass().toString() == "Tumor" }
def stromaCount = regionAnnotations.count { it.getPathClass().toString() == "Stroma" }
def otherCount  = regionAnnotations.count { it.getPathClass().toString() == "Other" }

print "Tumor annotations : " + tumorCount
print "Stroma annotations: " + stromaCount
print "Other annotations : " + otherCount

// ---------------- BUILD LABEL SERVER ----------------
// This uses the ANNOTATIONS directly to create mask labels.
def labelServer = new LabeledImageServer.Builder(imageData)
    .backgroundLabel(0, ColorTools.BLACK)
    .downsample(downsample)
    .addLabel("Tumor", 1, ColorTools.RED)
    .addLabel("Stroma", 2, ColorTools.GREEN)
    .addLabel("Other", 3, ColorTools.BLUE)
    .multichannelOutput(false)
    .build()

// ---------------- EXPORT TILES ----------------
// parentObjects(regionAnnotations) means tiles are exported around annotated regions only.
new TileExporter(imageData)
    .downsample(downsample)
    .tileSize(tileSize)
    .overlap(overlap)
    .parentObjects(regionAnnotations)
    .imageExtension(".png")
    .imageSubDir(imagesSubDir)
    .labeledServer(labelServer)
    .labeledImageExtension(".png")
    .labeledImageSubDir(masksSubDir)
    .labeledImageId("_mask")
    .writeTiles(baseOutput)

print "Done!"
print "Tiles written to: " + baseOutput
print "Mask labels:"
print "  0 = Background"
print "  1 = Tumor"
print "  2 = Stroma"
print "  3 = Other"