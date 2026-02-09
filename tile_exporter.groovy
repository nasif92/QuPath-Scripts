// Export 512x512 tiles + matching tumor/stroma masks
//  - Assumes annotations have PathClass "Tumor" and "Stroma"
//  - Produces single-channel masks with values: 0=background, 1=Tumor, 2=Stroma

import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.common.ColorTools
import qupath.lib.images.writers.TileExporter
import qupath.lib.common.GeneralTools

// ---- Get current image ----
def imageData = getCurrentImageData()
if (imageData == null) {
    print 'No image open!'
    return
}
def server = imageData.getServer()

// ---- Output folders ----
def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
def baseOutput = buildFilePath(PROJECT_BASE_DIR, 'tiles', name)
mkdirs(baseOutput)

def imagesSubDir = 'images'
def masksSubDir  = 'masks'

// ---- Export resolution (full-res) ----
double pixelSize = server.getPixelCalibration().getAveragedPixelSize()  // native µm/px
double requestedPixelSize = pixelSize                                  // same as native
double downsample = requestedPixelSize / pixelSize                     // = 1.0

// ---- Label server (single-channel mask: 0=bg, 1=Tumor, 2=Stroma) ----
def labelServer = new LabeledImageServer.Builder(imageData)
    .backgroundLabel(0, ColorTools.BLACK)  // background = 0
    .downsample(downsample)                // must match TileExporter downsample
    .addLabel('Tumor', 1)                  // PathClass 'Tumor' -> label 1
    .addLabel('Stroma', 2)                 // PathClass 'Stroma' -> label 2
    .multichannelOutput(false)             // single-channel integer mask
    .build()

// ---- Tile export: images + masks ----
new TileExporter(imageData)
    .downsample(downsample)                 // export resolution (full-res)
    .tileSize(512)                          // tile size in pixels
    .overlap(64)                            // overlap in pixels (set 0 if you don’t want overlap)
    .annotatedTilesOnly(true)               // only export tiles that intersect ANY annotation
    .imageExtension('.png')                 // RGB tile format
    .imageSubDir(imagesSubDir)              // tiles/<slide>/images
    .labeledServer(labelServer)             // use our label server
    .labeledImageExtension('.png')          // mask tiles format
    .labeledImageSubDir(masksSubDir)        // tiles/<slide>/masks
    .labeledImageId('_mask')                // filenames: tile_xxx_mask.png
    .writeTiles(baseOutput)                 // root output folder

print "Done! Tiles written to: " + baseOutput
