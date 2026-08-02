# QuPath-Scripts

Step-by-step process for running the QuPath scripts to extract features needed for ANN/MLP
1. Import images into a new project

2. Run "import_annotations.groovy" to import the annotations from geojson format exported earlier from QuPath. Make sure to check the path in the script (3rd batch/5th batch).

3. Run "import_detections.groovy" to import the detections which are in geojson from cellpose-dino. The detections should be within the annotations according to the script.

4. Run "assign_classes_gt.groovy" which assigns the classes to all the detections within a classified annotation. 

5. **Important** Run "featuring.groovy" to make sure you have all the features needed for mlp or ANN training. 
