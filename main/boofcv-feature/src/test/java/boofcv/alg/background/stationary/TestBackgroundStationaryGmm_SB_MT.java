/*
 * Copyright (c) 2021, Peter Abeles. All Rights Reserved.
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

package boofcv.alg.background.stationary;

import boofcv.alg.background.BackgroundModelStationary;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;

class TestBackgroundStationaryGmm_SB_MT extends CompareBackgroundStationarySingleToMultiChecks {
	public TestBackgroundStationaryGmm_SB_MT() {
		imageTypes.add(ImageType.single(GrayU8.class));
		imageTypes.add(ImageType.single(GrayF32.class));
	}

	@Override public <T extends ImageBase<T>> BackgroundModelStationary<T>
	create( boolean singleThread, ImageType<T> imageType ) {
		if (singleThread) {
			return new BackgroundStationaryGmm_SB(1000.0f, 0.001f, 10, imageType);
		}
		return new BackgroundStationaryGmm_SB_MT(1000.0f, 0.001f, 10, imageType);
	}
}

