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

package boofcv.alg.disparity.block.select;

import boofcv.alg.disparity.block.DisparitySelect;
import boofcv.struct.image.GrayF32;

/**
 * <p>
 * Implementation of {@link SelectErrorWithChecks_S32} that adds sub-pixel accuracy. Using
 * equation (3) from [1]:<br>
 *
 * d_sub = d + (C0 - C2)/(2*(C0 - 2*C1 + C2)<br>
 *
 * where C0,C1,C2 is the cost value, before, at, and after the selected disparity.
 * </p>
 *
 * <p>
 * [1] Wannes van der Mark and Dariu M. Gavrila, "Real-Time Dense Stereo for Intelligent Vehicles"
 * IEEE Trans. Intelligent Transportation Systems, Vol 7., No 1. March 2006.
 * </p>
 *
 * @author Peter Abeles
 */
public class SelectErrorSubpixel {

	/**
	 * For scores of type int[]
	 */
	public static class S32_F32 extends SelectErrorWithChecks_S32<GrayF32> {
		public S32_F32( int maxError, int rightToLeftTolerance, double texture ) {
			super(maxError, rightToLeftTolerance, texture, GrayF32.class);
		}

		S32_F32( S32_F32 original ) {
			super(original);
		}

		@Override
		protected void setDisparity( int index, int disparityValue ) {

			if (disparityValue <= 0 || disparityValue >= localRange - 1) {
				imageDisparity.data[index] = disparityValue;
			} else {
				int c0 = columnScore[disparityValue - 1];
				int c1 = columnScore[disparityValue];
				int c2 = columnScore[disparityValue + 1];

				float offset = (float)(c0 - c2)/(float)(2*(c0 - 2*c1 + c2));

				imageDisparity.data[index] = disparityValue + offset;
			}
		}

		@Override
		protected void setDisparityInvalid( int index ) {
			imageDisparity.data[index] = invalidDisparity;
		}

		@Override
		public DisparitySelect<int[], GrayF32> concurrentCopy() {
			return new S32_F32(this);
		}
	}

	/**
	 * For scores of type float[]
	 */
	public static class F32_F32 extends SelectErrorWithChecks_F32<GrayF32> {
		public F32_F32( int maxError, int rightToLeftTolerance, double texture ) {
			super(maxError, rightToLeftTolerance, texture, GrayF32.class);
		}

		F32_F32( F32_F32 original ) {
			super(original);
		}

		@Override
		protected void setDisparity( int index, int disparityValue ) {

			if (disparityValue <= 0 || disparityValue >= localRange - 1) {
				imageDisparity.data[index] = disparityValue;
			} else {
				float c0 = columnScore[disparityValue - 1];
				float c1 = columnScore[disparityValue];
				float c2 = columnScore[disparityValue + 1];

				float offset = (c0 - c2)/(2f*(c0 - 2f*c1 + c2));

				imageDisparity.data[index] = disparityValue + offset;
			}
		}

		@Override
		protected void setDisparityInvalid( int index ) {
			imageDisparity.data[index] = invalidDisparity;
		}

		@Override
		public DisparitySelect<float[], GrayF32> concurrentCopy() {
			return new F32_F32(this);
		}
	}
}
