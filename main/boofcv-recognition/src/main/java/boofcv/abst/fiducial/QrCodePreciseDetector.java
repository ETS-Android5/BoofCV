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

package boofcv.abst.fiducial;

import boofcv.abst.filter.binary.BinaryContourHelper;
import boofcv.abst.filter.binary.InputToBinary;
import boofcv.alg.distort.LensDistortionNarrowFOV;
import boofcv.alg.fiducial.qrcode.*;
import boofcv.alg.shapes.polygon.DetectPolygonBinaryGrayRefine;
import boofcv.alg.shapes.polygon.DetectPolygonFromContour;
import boofcv.misc.MovingAverage;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageGray;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A QR-Code detector which is designed to find the location of corners in the finder pattern precisely.
 */
public class QrCodePreciseDetector<T extends ImageGray<T>> implements QrCodeDetector<T> {
	@Getter QrCodePositionPatternDetector<T> detectPositionPatterns;
	@Getter QrCodePositionPatternGraphGenerator graphPositionPatterns = new QrCodePositionPatternGraphGenerator();
	@Getter QrCodeDecoderImage<T> decoder;
	InputToBinary<T> inputToBinary;
	Class<T> imageType;

	BinaryContourHelper contourHelper;

	// runtime profiling
	boolean profiler = false;
	protected MovingAverage milliBinary = new MovingAverage(0.8);
	protected MovingAverage milliDecoding = new MovingAverage(0.8);

	public QrCodePreciseDetector( InputToBinary<T> inputToBinary,
								  QrCodePositionPatternDetector<T> detectPositionPatterns,
								  @Nullable String forceEncoding,
								  String defaultEncoding,
								  boolean copyBinary, Class<T> imageType ) {
		this.inputToBinary = inputToBinary;
		this.detectPositionPatterns = detectPositionPatterns;
		this.decoder = new QrCodeDecoderImage<>(forceEncoding, defaultEncoding, imageType);
		this.imageType = imageType;
		this.contourHelper = new BinaryContourHelper(detectPositionPatterns.getSquareDetector().getDetector().getContourFinder(), copyBinary);
	}

	@Override
	public void process( T gray ) {
		long time0 = System.nanoTime();
		contourHelper.reshape(gray.width, gray.height);
		inputToBinary.process(gray, contourHelper.withoutPadding());
		long time1 = System.nanoTime();
		milliBinary.update((time1 - time0)*1e-6);

		if (profiler)
			System.out.printf("qrcode: binary %5.2f ", milliBinary.getAverage());

		// Find position patterns and create a graph
		detectPositionPatterns.process(gray, contourHelper.padded());
		List<PositionPatternNode> positionPatterns = detectPositionPatterns.getPositionPatterns().toList();
		graphPositionPatterns.process(positionPatterns);

		if (profiler) {
			DetectPolygonFromContour<T> detectorPoly = detectPositionPatterns.getSquareDetector().getDetector();
			System.out.printf(" contour %5.1f shapes %5.1f adjust_bias %5.2f PosPat %6.2f",
					detectorPoly.getMilliContour(), detectorPoly.getMilliShapes(),
					detectPositionPatterns.getSquareDetector().getMilliAdjustBias(),
					detectPositionPatterns.getProfilingMS().getAverage());
		}

		time0 = System.nanoTime();
		decoder.process(positionPatterns, gray);
		time1 = System.nanoTime();
		milliDecoding.update((time1 - time0)*1e-6);

		if (profiler)
			System.out.printf(" decoding %5.1f\n", milliDecoding.getAverage());
	}

	@Override
	public List<QrCode> getDetections() {
		return decoder.getFound();
	}

	@Override
	public List<QrCode> getFailures() {
		return decoder.getFailures();
	}

	/**
	 * <p>Specifies transforms which can be used to change coordinates from distorted to undistorted and the opposite
	 * coordinates. The undistorted image is never explicitly created.</p>
	 *
	 * @param width Input image width. Used in sanity check only.
	 * @param height Input image height. Used in sanity check only.
	 * @param model distortion model. Null to remove a distortion model.
	 */
	public void setLensDistortion( int width, int height, @Nullable LensDistortionNarrowFOV model ) {
		detectPositionPatterns.setLensDistortion(width, height, model);
		decoder.setLensDistortion(width, height, model);
	}

	public GrayU8 getBinary() {
		return contourHelper.withoutPadding();
	}

	public void setProfilerState( boolean active ) {
		profiler = active;
	}

	public void resetRuntimeProfiling() {
		milliBinary.reset();
		milliDecoding.reset();
		detectPositionPatterns.resetRuntimeProfiling();
	}

	public DetectPolygonBinaryGrayRefine<T> getSquareDetector() {
		return detectPositionPatterns.getSquareDetector();
	}

	@Override
	public Class<T> getImageType() {
		return imageType;
	}
}
