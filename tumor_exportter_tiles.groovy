import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.common.ColorTools
import qupath.lib.images.writers.TileExporter
import qupath.lib.common.GeneralTools

def imageData = getCurrentImageData()
if (imageData == null) {
    print 'No image open!'
    return
}
def server = imageData.getServer()

def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
def baseOutput = buildFilePath(PROJECT_BASE_DIR, 'tiles', name)
mkdirs(baseOutput)

def imagesSubDir = 'images'
def masksSubDir  = 'masks'

// native resolution
double pixelSize = server.getPixelCalibration().getAveragedPixelSize()
double requestedPixelSize = pixelSize
double downsample = requestedPixelSize / pixelSize

// ---- Label server: use cells instead of annotations ----
// ---- Label server: tumor only (0 = bg, 1 = Tumor) ----
def labelServer = new LabeledImageServer.Builder(imageData)
    .useCells()                          // use cell objects
    .backgroundLabel(0, ColorTools.BLACK)
    .downsample(downsample)
    .addLabel('Tumor', 1)               // ONLY Tumor cells -> label 1
    // no Stroma line -> Stroma cells become background (0)
    .multichannelOutput(false)
    .build()

def hierarchy = imageData.getHierarchy()
def tumorAnnotations = hierarchy.getAnnotationObjects()
    .findAll { it.getPathClass()?.toString() == 'Tumor' }

def stromaAnnotation = hierarchy.getAnnotationObjects()
    .findAll { it.getPathClass()?.toString() == 'Stroma'
    }
    
new TileExporter(imageData)
    .downsample(downsample)
    .tileSize(512)
    .overlap(64)
    .parentObjects(tumorAnnotations)     // tiles only around Tumor annotations
    .imageExtension('.png')
    .imageSubDir(imagesSubDir)
    .labeledServer(labelServer)
    .labeledImageExtension('.png')
    .labeledImageSubDir(masksSubDir)
    .labeledImageId('_mask')
    .writeTiles(baseOutput)

print "Done! Tumor-only tiles written to: " + baseOutput
