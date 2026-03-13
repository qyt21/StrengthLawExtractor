# StrengthLawExtractor (Fiji Plugin)
StrengthLawExtractor: An open-source Fiji plugin for automated extraction of 3D Minkowski functionals from X-ray micro-CT geo-material data

## Overview
StrengthLawExtractor is a Fiji/ImageJ plugin for computing geometric morphometric descriptors of porous materials from 3D binary image stacks.
The plugin analyzes the pore phase and reports four descriptors required for strength–structure relations:

  - Porosity 
      
  - Surface area (mesh mode)
      
  - Integrated mean curvature
      
  - Euler characteristic

The analysis can be performed on the entire specimen or on a user-defined volume of interest (VOI).

## Requirements
1. Fiji

2. A 3D binary image stack (pores must be either black or white)

## Installation
1. Download the plugin .jar file.

2. Copy it into the Fiji plugins folder:
   
   Fiji.app/plugins/ 

3. Restart Fiji.
   
    The plugin will appear in:
    
    Plugins → StrengthLawExtractor

## Basic Usage
1. Open a 3D image stack in Fiji.

2. Convert the stack to a binary image.

3. Run the plugin:

    Plugins → StrengthLawExtractor

4. Set parameters:
   
  - pore color (black or white)
  
  - voxel size
  
  - ROI mode
  
  - computation mode
  
5. Click Compute Features.
   
    Results are displayed in a ResultsTable and can be exported as .csv.

## Quick Test

To verify the plugin:

  1. Open any binary 3D stack in Fiji (for example, the given test file).
  
  2. Run Plugins → StrengthLawExtractor.
  
  3. Use default settings.
  
  4. Click Compute Features.

  A results window should appear reporting:
   
  - porosity
    
  - mean curvature
    
  - Euler characteristic
    
  - surface area 


<img width="2286" height="1235" alt="eg" src="https://github.com/user-attachments/assets/0293288d-3113-416c-82df-3c001bb5642d" />


### Notes
Mesh mode computes surface area using marching cubes.
Voxel mode is faster and suitable for previews.
Geometry export options are currently experimental.



If you use this plugin in your research, please cite it as:
Q. Tian and L. E. Dalton, "StrengthLawExtractor: A Fiji plugin for 3D morphological feature extraction from X-ray micro-CT data," arXiv:2510.17279, 2025. [Online]. Available: https://arxiv.org/abs/2510.17279.
