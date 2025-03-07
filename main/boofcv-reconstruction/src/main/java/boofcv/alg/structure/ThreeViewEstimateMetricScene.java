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

package boofcv.alg.structure;

import boofcv.abst.geo.bundle.BundleAdjustment;
import boofcv.abst.geo.bundle.PruneStructureFromSceneMetric;
import boofcv.abst.geo.bundle.SceneObservations;
import boofcv.abst.geo.bundle.SceneStructureMetric;
import boofcv.alg.geo.bundle.cameras.BundlePinholeSimplified;
import boofcv.alg.geo.robust.RansacProjective;
import boofcv.alg.geo.selfcalib.MetricCameraTriple;
import boofcv.factory.geo.*;
import boofcv.misc.BoofMiscOps;
import boofcv.misc.ConfigConverge;
import boofcv.struct.calib.CameraPinhole;
import boofcv.struct.calib.ElevateViewInfo;
import boofcv.struct.geo.AssociatedTriple;
import georegression.geometry.ConvertRotation3D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import georegression.struct.so.Rodrigues_F64;
import lombok.Getter;
import org.ddogleg.optimization.lm.ConfigLevenbergMarquardt;
import org.ddogleg.struct.VerbosePrint;
import org.ejml.dense.row.CommonOps_DDRM;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static boofcv.alg.geo.MultiViewOps.triangulatePoints;

/**
 * Estimates the metric scene's structure given a set of sparse features associations from three views. This is
 * intended to give the best possible solution from the sparse set of matching features. Its internal
 * methods are updated as better strategies are found.
 *
 * Assumptions:
 * <ul>
 *     <li>Principle point is zero</li>
 *     <li>Zero skew</li>
 *     <li>fx = fy approximately</li>
 * </ul>
 *
 * The zero principle point is enforced prior to calling {@link #process} by subtracting the image center from
 * each pixel observations.
 *
 * Steps:
 * <ol>
 *     <li>Fit Trifocal tensor using RANSAC</li>
 *     <li>Get and refine camera matrices</li>
 *     <li>Compute dual absolute quadratic</li>
 *     <li>Estimate intrinsic parameters from DAC</li>
 *     <li>Estimate metric scene structure</li>
 *     <li>Sparse bundle adjustment</li>
 *     <li>Tweak parameters and sparse bundle adjustment again</li>
 * </ol>
 *
 * @author Peter Abeles
 */
@SuppressWarnings("NullAway.Init")
public class ThreeViewEstimateMetricScene implements VerbosePrint {
	// TODO modify so that you can tell it if each view has the same intrinsics or not
	// TODO consider changing from RANSAC to LSMED. It provides better performance when the error tolerance is
	//      unreasonably high. This could indicate that features are not matching perfectly but close.

	// Make all configurations public for ease of manipulation
	public ConfigPixelsToMetric configSelfCalib = new ConfigPixelsToMetric();
	public ConfigRansac configRansac = new ConfigRansac();
	public ConfigLevenbergMarquardt configLM = new ConfigLevenbergMarquardt();
	public ConfigBundleAdjustment configSBA = new ConfigBundleAdjustment();
	public ConfigConverge convergeSBA = new ConfigConverge(1e-6, 1e-6, 100);

	/** It can assume that all views are generated from a single camera */
	public boolean singleCamera = true;

	// estimating the trifocal tensor and storing which observations are in the inlier set
	public RansacProjective<MetricCameraTriple, AssociatedTriple> ransac;
	public List<AssociatedTriple> inliers;

	// how much and where it should print to
	private @Nullable PrintStream verbose;

	// storage for pinhole cameras
	public final List<CameraPinhole> listPinhole = new ArrayList<>();

	// Refines the structure
	public BundleAdjustment<SceneStructureMetric> bundleAdjustment;

	// Bundle adjustment data structure and tuning parameters
	public @Getter SceneStructureMetric structure;
	public SceneObservations observations;

	// If a positive number the focal length will be assumed to be that
	public double manualFocalLength = -1;

	// How many features it will keep when pruning
	public double pruneFraction = 0.7;

	// shape of input images.
//	private int width, height; // TODO Get size for each image individually

	// metric location of each camera. The first view is always identity
//	protected List<Se3_F64> listWorldToView = new ArrayList<>();

	/**
	 * Sets configurations to their default value
	 */
	public ThreeViewEstimateMetricScene() {
		configRansac.iterations = 1000;
		configRansac.inlierThreshold = 4;

		configLM.dampeningInitial = 1e-3;
		configLM.hessianScaling = false;
		configSBA.configOptimizer = configLM;
	}

	/**
	 * Determines the metric scene. The principle point is assumed to be zero in the passed in pixel coordinates.
	 * Typically this is done by subtracting the image center from each pixel coordinate for each view.
	 *
	 * @param associated List of associated features from 3 views. pixels
	 * @param width width of all images
	 * @param height height of all images
	 * @return true if successful or false if it failed
	 */
	public boolean process( List<AssociatedTriple> associated, int width, int height ) {
		init(width, height);

		// Fit a trifocal tensor to the input observations
		if (!robustSelfCalibration(associated))
			return false;

		// Run bundle adjustment while make sure a valid solution is found
		setupMetricBundleAdjustment(inliers);

		bundleAdjustment = FactoryMultiView.bundleSparseMetric(configSBA);
		findBestValidSolution(bundleAdjustment);

		// Prune outliers and run bundle adjustment one last time
		pruneOutliers(bundleAdjustment);

		return true;
	}

	@SuppressWarnings("NullAway")
	private void init( int width, int height ) {
		// TODO let it specify image shape for each view independently
		ransac = FactoryMultiViewRobust.metricThreeViewRansac(configSelfCalib, configRansac);

		// Let it know some information about the cameras
		for (int idx = 0; idx < 3; idx++) {
			int camId = singleCamera ? 0 : idx;
			ransac.setView(idx, new ElevateViewInfo(width, height, camId));
		}
		structure = null;
		observations = null;
	}

	/**
	 * Fits a trifocal tensor to the list of matches features using a robust method
	 */
	private boolean robustSelfCalibration( List<AssociatedTriple> associated ) {
		// Fit a trifocal tensor to the observations robustly
		if (!ransac.process(associated)) {
			if (verbose != null) verbose.println("RANSAC failed!");
			return false;
		}

		inliers = ransac.getMatchSet();

		// TODO make configurable
		if (inliers.size() < associated.size()/10) {
			if (verbose != null) verbose.println("Too few inliers: " + inliers.size() + "/" + associated.size());
			return false;
		}

		if (verbose != null) verbose.println("Remaining after RANSAC " + inliers.size() + " / " + associated.size());

		if (singleCamera) {
			averageIntrinsicParameters(ransac.getModelParameters());
		} else {
			listPinhole.clear();
			for (int i = 0; i < 3; i++) {
				listPinhole.add(ransac.getModelParameters().getIntrinsics(i));
			}
		}
		return true;
	}

	/**
	 * Prunes the features with the largest reprojection error
	 */
	private void pruneOutliers( BundleAdjustment<SceneStructureMetric> bundleAdjustment ) {
		// see if it's configured to not prune
		if (pruneFraction == 1.0)
			return;
		if (verbose != null) verbose.println("Pruning Outliers");

		var pruner = new PruneStructureFromSceneMetric(structure, observations);
		pruner.pruneObservationsByErrorRank(pruneFraction);
		pruner.pruneViews(10);
		pruner.pruneUnusedMotions();
		pruner.prunePoints(1);
		bundleAdjustment.setParameters(structure, observations);
		double before = bundleAdjustment.getFitScore();
		bundleAdjustment.optimize(structure);
		if (verbose != null) verbose.println("   before " + before + " after " + bundleAdjustment.getFitScore());

		if (verbose != null) {
			verbose.println("\nCamera");
			for (int i = 0; i < structure.cameras.size; i++) {
				verbose.println("  " + Objects.requireNonNull(structure.cameras.data[i].getModel()).toString());
			}
			verbose.println("\nworldToView");
			for (int i = 0; i < structure.views.size; i++) {
				if (verbose != null) {
					Se3_F64 se = structure.getParentToView(i);
					Rodrigues_F64 rod = ConvertRotation3D_F64.matrixToRodrigues(se.R, null);
					verbose.println("  T=" + se.T + "  R=" + rod);
				}
			}
		}
	}

	/**
	 * Tries a bunch of stuff to ensure that it can find the best solution which is physically possible
	 */
	private void findBestValidSolution( BundleAdjustment<SceneStructureMetric> bundleAdjustment ) {
		// Specifies convergence criteria
		bundleAdjustment.configure(convergeSBA.ftol, convergeSBA.gtol, convergeSBA.maxIterations);

		bundleAdjustment.setParameters(structure, observations);
		bundleAdjustment.optimize(structure);

		// ensure that the points are in front of the camera and are a valid solution
		if (checkBehindCamera(structure)) {
			if (verbose != null)
				verbose.println("  #1 Points Behind. Flipping view");
			flipAround(structure, observations);
			bundleAdjustment.setParameters(structure, observations);
			bundleAdjustment.optimize(structure);
		}

		double bestScore = bundleAdjustment.getFitScore();
		if (verbose != null) verbose.println("First Pass: SBA score " + bestScore);
		List<Se3_F64> bestPose = new ArrayList<>();
		List<BundlePinholeSimplified> bestCameras = new ArrayList<>();
		for (int i = 0; i < structure.cameras.size; i++) {
			BundlePinholeSimplified c = Objects.requireNonNull(structure.cameras.data[i].getModel());
			bestCameras.add(c.copy());
		}

		for (int i = 0; i < structure.views.size; i++) {
			bestPose.add(structure.getParentToView(i).copy());
		}

		for (int i = 0; i < structure.cameras.size; i++) {
			BundlePinholeSimplified c = Objects.requireNonNull(structure.cameras.data[i].getModel());
			c.f = listPinhole.get(i).fx;
			c.k1 = c.k2 = 0;
		}
		// flip rotation assuming that it was done wrong
		for (int i = 1; i < structure.views.size; i++) {
			CommonOps_DDRM.transpose(structure.getParentToView(i).R);
		}
		triangulatePoints(structure, observations);

		bundleAdjustment.setParameters(structure, observations);
		bundleAdjustment.optimize(structure);

		if (checkBehindCamera(structure)) {
			if (verbose != null)
				verbose.println("  #2 Points Behind. Flipping view");
			flipAround(structure, observations);
			bundleAdjustment.setParameters(structure, observations);
			bundleAdjustment.optimize(structure);
		}

		// revert to old settings
		if (verbose != null)
			verbose.println(" First Pass / Transpose(R) = " + bestScore + " / " + bundleAdjustment.getFitScore());
		if (bundleAdjustment.getFitScore() > bestScore) {
			if (verbose != null)
				verbose.println("  recomputing old structure");
			for (int i = 0; i < structure.cameras.size; i++) {
				BundlePinholeSimplified c = Objects.requireNonNull(structure.cameras.data[i].getModel());
				c.setTo(bestCameras.get(i));
			}
			for (int i = 0; i < structure.views.size; i++) {
				structure.getParentToView(i).setTo(bestPose.get(i));
			}
			triangulatePoints(structure, observations);
			bundleAdjustment.setParameters(structure, observations);
			bundleAdjustment.optimize(structure);
			if (verbose != null)
				verbose.println("  score after reverting = " + bundleAdjustment.getFitScore() + "  original " + bestScore);
		}
	}

	/**
	 * Using the initial metric reconstruction, provide the initial configurations for bundle adjustment
	 */
	private void setupMetricBundleAdjustment( List<AssociatedTriple> inliers ) {
		// Construct bundle adjustment data structure
		structure = new SceneStructureMetric(false);
		structure.initialize(listPinhole.size(), 3, inliers.size());
		observations = new SceneObservations();
		observations.initialize(3);

		for (int i = 0; i < listPinhole.size(); i++) {
			CameraPinhole cp = listPinhole.get(i);
			BundlePinholeSimplified bp = new BundlePinholeSimplified();

			bp.f = cp.fx;

			structure.setCamera(i, false, bp);
		}

		MetricCameraTriple found = ransac.getModelParameters();
		for (int i = 0; i < 3; i++) {
			structure.setView(i, singleCamera ? 0 : i, i == 0, found.getTransform(i));
		}

		for (int i = 0; i < inliers.size(); i++) {
			AssociatedTriple t = inliers.get(i);

			observations.getView(0).add(i, (float)t.p1.x, (float)t.p1.y);
			observations.getView(1).add(i, (float)t.p2.x, (float)t.p2.y);
			observations.getView(2).add(i, (float)t.p3.x, (float)t.p3.y);

			structure.connectPointToView(i, 0);
			structure.connectPointToView(i, 1);
			structure.connectPointToView(i, 2);
		}
		// Initial estimate for point 3D locations
		triangulatePoints(structure, observations);
	}

	/**
	 * Assume that there's only really one camera being used and take all the indepdent estimates and average them
	 */
	private void averageIntrinsicParameters( MetricCameraTriple results ) {
		listPinhole.clear();
		listPinhole.add(results.getIntrinsics(0));

		CameraPinhole ave = listPinhole.get(0);
		for (int i = 1; i < 3; i++) {
			CameraPinhole a = results.getIntrinsics(i);
			ave.fx += a.fx;
			ave.fy += a.fy;
			ave.cx += a.cx;
			ave.cy += a.cy;
		}
		ave.fx /= 3;
		ave.fy /= 3;
		ave.cx /= 3;
		ave.cy /= 3;
	}

	/**
	 * Checks to see if a solution was converged to where the points are behind the camera. This is
	 * physically impossible
	 */
	private boolean checkBehindCamera( SceneStructureMetric structure ) {
		int totalBehind = 0;
		Point3D_F64 X = new Point3D_F64();
		for (int i = 0; i < structure.points.size; i++) {
			structure.points.data[i].get(X);
			if (X.z < 0)
				totalBehind++;
		}

		if (verbose != null) {
			verbose.println("points behind " + totalBehind + " / " + structure.points.size);
		}

		return totalBehind > structure.points.size/2;
	}

	/**
	 * Flip the camera pose around. This seems to help it converge to a valid solution if it got it backwards
	 * even if it's not technically something which can be inverted this way
	 */
	private static void flipAround( SceneStructureMetric structure, SceneObservations observations ) {
		// The first view will be identity
		for (int i = 1; i < structure.views.size; i++) {
			Se3_F64 w2v = structure.getParentToView(i);
			w2v.setTo(w2v.invert(null));
		}
		triangulatePoints(structure, observations);
	}

	@Override
	public void setVerbose( @Nullable PrintStream out, @Nullable Set<String> configuration ) {
		this.verbose = BoofMiscOps.addPrefix(this, out);
	}
}
