import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.images.writers.TileExporter
import qupath.lib.common.GeneralTools
import qupath.lib.common.ColorTools
import qupath.lib.objects.PathObjects

// ============================================================
// CLASS SCHEME (must match Python class_scheme.py)
//   Tumor, Stroma, Immune  -> own classes
//   Normal-*               -> collapsed to "Normal" in Python
//   Necrosis               -> "Other" in Python
//   Ignore*, null          -> dropped in Python
// Here we EXPORT all of them (except Ignore/null) so the cells exist;
// the collapsing/dropping happens in Python via class_scheme.py.
// ============================================================
def keepCellClasses = [
    "Tumor", "Stroma", "Immune cells",
    "Normal-GI-Mucosa", "Normal-foveola", "Normal-Squamous", "Normal-Brunner",
    "Normal-endometrial-Ep", "Normal-Glands", "Normal-Smooth-Muscle",
    "Necrosis", "Other"
] as Set
def keepRegionClasses = keepCellClasses
// NOTE: keepCellClasses/keepRegionClasses are now ONLY used to define which
// classes get a colored channel in the mask palette below. They no longer
// filter which annotations count as "regions" or which cell detections are
// exported - every annotation (any class, including unclassified) defines
// tile boundaries, and every detection inside those tiles is exported.

def tileSize    = 256
def overlap     = 0
double downsample = 1.0
String imageExt = ".png"
int minCellsPerTile = 5   // tiles with fewer detections than this are deleted from disk entirely

def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()
def server    = imageData.getServer()
def imageName = server.getMetadata().getName()

if (imageName.toUpperCase().contains("HE")) {
    println "Skipping H&E image: " + imageName
    return
}
println "Processing: " + imageName

def name = GeneralTools.getNameWithoutExtension(imageName)
def baseOutput = buildFilePath(PROJECT_BASE_DIR, "tiles_multiclass_regions", name)
def imagesDir  = buildFilePath(baseOutput, "images")
mkdirs(baseOutput)

def regionAnnotations = hierarchy.getAnnotationObjects()
if (regionAnnotations.isEmpty()) { println "No annotations - skipping."; return }
println "Found region annotations (all classes): " + regionAnnotations.size()

// --------------------------------------------------------------
// Assign a stable integer ID to every kept annotation so nesting
// (outer region vs. inner detail annotation) survives into the
// exported GeoJSON and can be cross-referenced from the CSV.
// --------------------------------------------------------------
def annotationIndex = [:]
regionAnnotations.eachWithIndex { ann, i ->
    annotationIndex[ann] = i
    ann.getMeasurementList().put("__export_annotation_id", i as double)
}
// Precompute area once per annotation (avoids recomputation per cell).
def annotationArea = regionAnnotations.collectEntries { ann -> [(ann): ann.getROI().getArea()] }

def geojsonPath = buildFilePath(baseOutput, name + "_regions.geojson")
exportObjectsToGeoJson(regionAnnotations, geojsonPath, "FEATURE_COLLECTION")
println "Region GeoJSON exported: " + geojsonPath

deselectAll()
selectObjects(regionAnnotations)
// labelServer MUST include every kept class, or annotatedTilesOnly skips tiles
// over the missing classes' regions -> their cells get no tile -> dropped.
def labelServer = new LabeledImageServer.Builder(imageData)
    .backgroundLabel(0, ColorTools.makeRGB(0, 0, 0))
    .downsample(downsample)
    .addLabel("Tumor",                 1, ColorTools.makeRGB(255,   0,   0))
    .addLabel("Stroma",                2, ColorTools.makeRGB(  0, 200,   0))
    .addLabel("Immune cells",          3, ColorTools.makeRGB(  0,   0, 255))
    .addLabel("Normal-GI-Mucosa",      4, ColorTools.makeRGB(  0, 255, 255))
    .addLabel("Normal-foveola",        5, ColorTools.makeRGB(255,   0, 255))
    .addLabel("Normal-Squamous",       6, ColorTools.makeRGB(255, 255,   0))
    .addLabel("Normal-Brunner",        7, ColorTools.makeRGB(255, 165,   0))
    .addLabel("Normal-endometrial-Ep", 8, ColorTools.makeRGB(220, 220, 220))
    .addLabel("Normal-Glands",         9, ColorTools.makeRGB(128, 128, 128))
    .addLabel("Normal-Smooth-Muscle", 10, ColorTools.makeRGB(255, 192, 203))
    .addLabel("Necrosis",             11, ColorTools.makeRGB( 64,  64,  64))
    .addLabel("Other",                12, ColorTools.makeRGB(150,   0, 150))
    .useCellNuclei()   // rasterize nucleus boundaries for cell objects, not whole-cell (nucleus+cytoplasm) shape
    .multichannelOutput(false)
    .build()

new TileExporter(imageData)
    .downsample(downsample).tileSize(tileSize).overlap(overlap)
    .annotatedTilesOnly(true).imageExtension(imageExt).imageSubDir("images")
    .labeledServer(labelServer).labeledImageExtension(imageExt)
    .labeledImageSubDir("masks").labeledImageId("_mask")
    .writeTiles(baseOutput)
deselectAll()
println "Tile + mask export complete."

def masksDir = buildFilePath(baseOutput, "masks")
def tilePattern = java.util.regex.Pattern.compile(/x=(\d+),\s*y=(\d+),\s*w=(\d+),\s*h=(\d+)/)

def maskFileByCoordKey = [:]
new File(masksDir).eachFile { f ->
    if (!f.name.endsWith(imageExt)) return
    def m = tilePattern.matcher(f.name)
    if (m.find()) maskFileByCoordKey["${m.group(1)}_${m.group(2)}_${m.group(3)}_${m.group(4)}"] = f
}

def exportedTiles = []
new File(imagesDir).eachFile { f ->
    if (!f.name.endsWith(".png")) return
    def m = tilePattern.matcher(f.name)
    if (m.find()) exportedTiles << [x:m.group(1).toInteger(), y:m.group(2).toInteger(),
                                    w:m.group(3).toInteger(), h:m.group(4).toInteger(),
                                    imageFile:f,
                                    maskFile:maskFileByCoordKey["${m.group(1)}_${m.group(2)}_${m.group(3)}_${m.group(4)}"]]
}
println "Tiles found on disk: " + exportedTiles.size()
if (exportedTiles.isEmpty()) { println "No exported tiles - skipping."; return }

// --------------------------------------------------------------
// Find every kept annotation containing a cell (not just the first
// match). Sorted by area so we can pick the innermost ("detail")
// and outermost ("region") annotation independently and consistently,
// regardless of hierarchy iteration order.
// --------------------------------------------------------------
def containingAnnotations = { cell ->
    double cx = cell.getROI().getCentroidX(); double cy = cell.getROI().getCentroidY()
    def hits = regionAnnotations.findAll { ann -> ann.getROI().contains(cx, cy) }
    return hits.sort { a, b -> annotationArea[a] <=> annotationArea[b] } // smallest first
}

def allCells = getCellObjects().findAll { cell ->
    return !containingAnnotations(cell).isEmpty()
}
println "All detections inside kept annotation regions: " + allCells.size()
if (allCells.isEmpty()) { println "No cells in kept regions - skipping."; return }

def getMeasure = { cell, String mname ->
    try { return cell.getMeasurementList().get(mname) } catch (Exception e) { return Double.NaN }
}
def fmt = { val -> (val == null || (val instanceof Double && val.isNaN())) ? "" : String.format("%.4f", val) }

def csvFile = new File(baseOutput, name + "_nucleus_instances.csv")
def nucleusShapeObjects = []   // exact nucleus polygons, exported separately, keyed by nucleus_id
int totalNuclei = 0, totalTiles = 0, tilesDropped = 0
csvFile.withWriter { writer ->
    writer.writeLine([
        "wsi_name","tile_x","tile_y","tile_w","tile_h","nucleus_id",
        "x_min","y_min","x_max","y_max","cx_wsi","cy_wsi","cx_in_tile","cy_in_tile",
        "label",
        "containing_annotation_ids",                         // pipe-separated, smallest area first
        "containing_annotation_classes",                     // pipe-separated, same order as above
        "annotation_nesting_depth",                           // how many kept annotations contain this cell
        "nucleus_area_um2","nucleus_circularity","nucleus_solidity",
        "nucleus_max_diameter_um","nucleus_min_diameter_um","nucleus_length_um",
        "nucleus_cell_area_ratio","cell_area_um2","cell_circularity",
        "cell_max_diameter_um","cell_min_diameter_um",
        "hem_nucleus_mean","hem_nucleus_std","hem_cyto_mean","hem_cyto_std",
        "hem_cell_mean","hem_cell_std","dab_nucleus_mean","dab_nucleus_std",
        "dab_cyto_mean","dab_cyto_std","dab_membrane_mean","dab_membrane_std",
        "dab_cell_mean","dab_cell_std",
        "nearby_detections_50um","dab_nucleus_mean_50um","dab_cyto_mean_50um",
        "nearby_detections_75um","dab_nucleus_mean_75um","dab_cyto_mean_75um"
    ].join(","))
    int nucleusId = 0
    exportedTiles.each { tile ->
        int tileX = tile.x, tileY = tile.y, tileW = tile.w, tileH = tile.h
        def tileCells = allCells.findAll { cell ->
            double cx = cell.getROI().getCentroidX(); double cy = cell.getROI().getCentroidY()
            cx >= tileX && cx < (tileX+tileW) && cy >= tileY && cy < (tileY+tileH)
        }
        if (tileCells.size() < minCellsPerTile) {
            tile.imageFile?.delete()
            tile.maskFile?.delete()
            tilesDropped++
            return
        }
        totalTiles++
        tileCells.each { cell ->
            def roi = cell.getROI(); def nucleusROI = cell.getNucleusROI() ?: roi
            double cxWsi = roi.getCentroidX(), cyWsi = roi.getCentroidY()
            double xMin = nucleusROI.getBoundsX(), yMin = nucleusROI.getBoundsY()

            def hits = containingAnnotations(cell)          // smallest area first
            def hitIds     = hits.collect { annotationIndex[it] }.join("|")
            def hitClasses = hits.collect { it.getPathClass()?.getName() ?: "" }.join("|")
            def thisNucleusId = nucleusId

            def cellClassName = cell.getPathClass()?.getName() ?: ""
            def nucShapeObj = PathObjects.createDetectionObject(nucleusROI, cell.getPathClass())
            nucShapeObj.getMeasurementList().put("nucleus_id", thisNucleusId as double)
            nucleusShapeObjects << nucShapeObj

            writer.writeLine([
                name, tileX, tileY, tileW, tileH, nucleusId++,
                fmt(xMin), fmt(yMin), fmt(xMin+nucleusROI.getBoundsWidth()),
                fmt(yMin+nucleusROI.getBoundsHeight()),
                fmt(cxWsi), fmt(cyWsi), fmt(cxWsi-tileX), fmt(cyWsi-tileY),
                cellClassName,
                hitIds, hitClasses,
                hits.size(),
                fmt(getMeasure(cell,"Nucleus: Area µm^2")), fmt(getMeasure(cell,"Nucleus: Circularity")),
                fmt(getMeasure(cell,"Nucleus: Solidity")), fmt(getMeasure(cell,"Nucleus: Max diameter µm")),
                fmt(getMeasure(cell,"Nucleus: Min diameter µm")), fmt(getMeasure(cell,"Nucleus: Length µm")),
                fmt(getMeasure(cell,"Nucleus/Cell area ratio")), fmt(getMeasure(cell,"Cell: Area µm^2")),
                fmt(getMeasure(cell,"Cell: Circularity")), fmt(getMeasure(cell,"Cell: Max diameter µm")),
                fmt(getMeasure(cell,"Cell: Min diameter µm")),
                fmt(getMeasure(cell,"Hematoxylin: Nucleus: Mean")), fmt(getMeasure(cell,"Hematoxylin: Nucleus: Std.Dev.")),
                fmt(getMeasure(cell,"Hematoxylin: Cytoplasm: Mean")), fmt(getMeasure(cell,"Hematoxylin: Cytoplasm: Std.Dev.")),
                fmt(getMeasure(cell,"Hematoxylin: Cell: Mean")), fmt(getMeasure(cell,"Hematoxylin: Cell: Std.Dev.")),
                fmt(getMeasure(cell,"DAB: Nucleus: Mean")), fmt(getMeasure(cell,"DAB: Nucleus: Std.Dev.")),
                fmt(getMeasure(cell,"DAB: Cytoplasm: Mean")), fmt(getMeasure(cell,"DAB: Cytoplasm: Std.Dev.")),
                fmt(getMeasure(cell,"DAB: Membrane: Mean")), fmt(getMeasure(cell,"DAB: Membrane: Std.Dev.")),
                fmt(getMeasure(cell,"DAB: Cell: Mean")), fmt(getMeasure(cell,"DAB: Cell: Std.Dev.")),
                fmt(getMeasure(cell,"Smoothed: 50 µm: Nearby detection counts")),
                fmt(getMeasure(cell,"Smoothed: 50 µm: DAB: Nucleus: Mean")),
                fmt(getMeasure(cell,"Smoothed: 50 µm: DAB: Cytoplasm: Mean")),
                fmt(getMeasure(cell,"Smoothed: 75 µm: Nearby detection counts")),
                fmt(getMeasure(cell,"Smoothed: 75 µm: DAB: Nucleus: Mean")),
                fmt(getMeasure(cell,"Smoothed: 75 µm: DAB: Cytoplasm: Mean"))
            ].join(","))
            totalNuclei++
        }
    }
}
def nucleiGeojsonPath = buildFilePath(baseOutput, name + "_nuclei.geojson")
exportObjectsToGeoJson(nucleusShapeObjects, nucleiGeojsonPath, "FEATURE_COLLECTION")
println "Nucleus polygon GeoJSON exported: " + nucleiGeojsonPath + " (" + nucleusShapeObjects.size() + " nuclei, matched by nucleus_id)"

println "─────────────────────────────────"
println "Nuclei exported: " + totalNuclei + " | Tiles kept: " + totalTiles + " | Tiles dropped (<" + minCellsPerTile + " cells, deleted from disk): " + tilesDropped
println "Detections    : ALL classes exported, ALL annotations used as regions (no class filtering, per Dr. Gilbert)"
println "Region annotations (with export IDs written to GeoJSON measurement list): " + regionAnnotations.size()
println "Done! (class collapsing/dropping happens in Python via class_scheme.py)"