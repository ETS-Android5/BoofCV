/*
 * Copyright (c) 2022, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.abst.fiducial.calib;

import boofcv.abst.filter.binary.InputToBinary;
import boofcv.abst.geo.calibration.DetectSingleFiducialCalibration;
import boofcv.alg.distort.LensDistortionNarrowFOV;
import boofcv.alg.fiducial.calib.circle.DetectCircleHexagonalGrid;
import boofcv.alg.fiducial.calib.circle.EllipseClustersIntoGrid.Grid;
import boofcv.alg.fiducial.calib.circle.EllipsesIntoClusters;
import boofcv.alg.fiducial.calib.circle.KeyPointsCircleHexagonalGrid;
import boofcv.alg.geo.calibration.CalibrationObservation;
import boofcv.alg.shapes.ellipse.BinaryEllipseDetector;
import boofcv.factory.filter.binary.FactoryThresholdBinary;
import boofcv.factory.shape.FactoryShapeDetector;
import boofcv.struct.distort.PixelTransform;
import boofcv.struct.distort.Point2Transform2_F32;
import boofcv.struct.distort.PointToPixelTransform_F32;
import boofcv.struct.geo.PointIndex2D_F64;
import boofcv.struct.image.GrayF32;
import georegression.metric.UtilAngle;
import georegression.struct.point.Point2D_F32;
import georegression.struct.point.Point2D_F64;
import org.ddogleg.struct.DogArray;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Calibration implementation of circle hexagonal grid fiducial.
 *
 * @author Peter Abeles
 * @see DetectCircleHexagonalGrid
 * @see KeyPointsCircleHexagonalGrid
 */
@SuppressWarnings({"NullAway.Init"})
public class CalibrationDetectorCircleHexagonalGrid implements DetectSingleFiducialCalibration {

	// Detectors the grids
	private DetectCircleHexagonalGrid<GrayF32> detector;
	// extracts key points from detected grid
	private KeyPointsCircleHexagonalGrid keypoint = new KeyPointsCircleHexagonalGrid();

	// Storage for 2D location of points on fiducial
	private List<Point2D_F64> layout;

	private CalibrationObservation results;

	double spaceToDiameter;

	/**
	 * Configures the detector based on the pass in configuration class
	 *
	 * @param configDet Configuration for detector and target description
	 */
	public CalibrationDetectorCircleHexagonalGrid( ConfigCircleHexagonalGrid configDet, ConfigGridDimen configGrid ) {

		InputToBinary<GrayF32> inputToBinary =
				FactoryThresholdBinary.threshold(configDet.thresholding, GrayF32.class);

		BinaryEllipseDetector<GrayF32> ellipseDetector =
				FactoryShapeDetector.ellipse(configDet.ellipse, GrayF32.class);

		spaceToDiameter = (configGrid.shapeDistance/configGrid.shapeSize);
		double spaceToRadius = 2.0*spaceToDiameter;

		double factor = 2*Math.sin(Math.PI/3) + 0.25;

		EllipsesIntoClusters e2c = new EllipsesIntoClusters(
				spaceToRadius*factor, configDet.ellipseSizeSimilarity, configDet.edgeIntensitySimilarityTolerance);

		detector = new DetectCircleHexagonalGrid<>(configGrid.numRows, configGrid.numCols, inputToBinary,
				ellipseDetector, e2c);


		layout = createLayout(detector.getRows(), detector.getColumns(), configGrid.shapeDistance);
	}

	@Override
	public boolean process( GrayF32 input ) {
		results = new CalibrationObservation(input.width, input.height);
		detector.process(input);

		List<Grid> grids = detector.getGrids();

		if (grids.size() != 1)
			return false;

		if (!keypoint.process(grids.get(0)))
			return false;

		DogArray<PointIndex2D_F64> foundPixels = keypoint.getKeyPoints();

		for (int i = 0; i < foundPixels.size; i++) {
			results.add(i, foundPixels.get(i).p);
		}
		return true;
	}

	@Override
	public CalibrationObservation getDetectedPoints() {
		return results;
	}

	@Override
	public List<Point2D_F64> getLayout() {
		return layout;
	}

	@Override
	public void setLensDistortion( @Nullable LensDistortionNarrowFOV distortion, int width, int height ) {
		if (distortion == null)
			detector.getEllipseDetector().setLensDistortion(null, null);
		else {
			Point2Transform2_F32 pointDistToUndist = distortion.undistort_F32(true, true);
			Point2Transform2_F32 pointUndistToDist = distortion.distort_F32(true, true);
			PixelTransform<Point2D_F32> distToUndist = new PointToPixelTransform_F32(pointDistToUndist);
			PixelTransform<Point2D_F32> undistToDist = new PointToPixelTransform_F32(pointUndistToDist);

			detector.getEllipseDetector().setLensDistortion(distToUndist, undistToDist);
		}
	}

	/**
	 * Specifies the physical location of each point on the 2D calibration plane. The fiducial is centered on the
	 * coordinate system
	 *
	 * @param numRows Number of rows
	 * @param numCols Number of columns
	 * @param centerDistance Space between each circle's center along x and y axis
	 * @return 2D locations
	 */
	public static List<Point2D_F64> createLayout( int numRows, int numCols, double centerDistance ) {

		List<Point2D_F64> ret = new ArrayList<>();

		double spaceX = centerDistance/2.0;
		double spaceY = centerDistance*Math.sin(UtilAngle.radian(60));

		double width = (numCols - 1)*spaceX;
		double height = (numRows - 1)*spaceY;

		for (int row = 0; row < numRows; row++) {
			double y = row*spaceY - height/2;
			for (int col = row%2; col < numCols; col += 2) {
				double x = col*spaceX - width/2;
				ret.add(new Point2D_F64(x, y));
			}
		}

		return ret;
	}

	public DetectCircleHexagonalGrid<GrayF32> getDetector() {
		return detector;
	}

	public KeyPointsCircleHexagonalGrid getKeypointFinder() {
		return keypoint;
	}

	public int getRows() {
		return detector.getRows();
	}

	public int getColumns() {
		return detector.getColumns();
	}

	/**
	 * Distance between centers to circle radius ratio
	 */
	public double getSpaceToDiameter() {
		return spaceToDiameter;
	}
}
