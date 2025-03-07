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

package boofcv.alg.fiducial.qrcode;

import lombok.Getter;
import org.ddogleg.struct.DogArray_I16;
import org.ddogleg.struct.DogArray_I32;

import java.util.Arrays;

/**
 * TODO Summarize
 *
 * <p>Code and code comments based on the tutorial at [1].</p>
 *
 * <p>[1] <a href="https://en.wikiversity.org/wiki/Reed–Solomon_codes_for_coders">Reed-Solomon Codes for Coders</a>
 * Viewed on September 28, 2017</p>
 *
 * @author Peter Abeles
 */
public class ReedSolomonCodes_U16 {
	GaliosFieldTableOps_U16 math;

	DogArray_I16 generator = new DogArray_I16();

	DogArray_I16 tmp0 = new DogArray_I16();
	DogArray_I16 tmp1 = new DogArray_I16();

	DogArray_I32 errorLocations = new DogArray_I32();
	DogArray_I16 errorLocatorPoly = new DogArray_I16();
	DogArray_I16 syndromes = new DogArray_I16();

	// Workspace for error correction. Precomputed to avoid calls to new
	DogArray_I16 err_eval = new DogArray_I16();
	DogArray_I16 errorX = new DogArray_I16();
	DogArray_I16 err_loc_prime_tmp = new DogArray_I16();

	/**
	 * Specifies if the base of the generator polynomial will be 0 or 1:  (x[i] + i + family)* ... *(x[n] + n + family).
	 *
	 * For QR this is 0 and for Aztec this is 1.
	 */
	@Getter int generatorBase;

	/**
	 * @param numBits Number of bits in each word
	 * @param primitive Primitive polynomial
	 * @param generatorBase Base for generator polynomial. 0 or 1
	 */
	public ReedSolomonCodes_U16( int numBits, int primitive, int generatorBase ) {
		if (generatorBase < 0 || generatorBase > 1)
			throw new IllegalArgumentException("generatorBase must be 0 or 1");
		math = new GaliosFieldTableOps_U16(numBits, primitive);
		this.generatorBase = generatorBase;
	}

	/**
	 * Given the input message compute the error correction code for it
	 *
	 * @param input Input message. Modified internally then returned to its initial state
	 * @param output error correction code
	 */
	public void computeECC( DogArray_I16 input, DogArray_I16 output ) {
		int N = generator.size - 1;
		input.extend(input.size + N);
		Arrays.fill(input.data, input.size - N, input.size, (short)0);

		math.polyDivide(input, generator, tmp0, output);

		input.size -= N;
	}

	/**
	 * Decodes the message and performs any necessary error correction
	 *
	 * @param input (Input) Corrupted Message (Output) corrected message
	 * @param ecc (Input) error correction code for the message
	 * @return true if it was successful or false if it failed
	 */
	public boolean correct( DogArray_I16 input, DogArray_I16 ecc ) {
		computeSyndromes(input, ecc, syndromes);
		findErrorLocatorPolynomialBM(syndromes, errorLocatorPoly);
		if (!findErrorLocations_BruteForce(errorLocatorPoly, input.size + ecc.size, errorLocations))
			return false;

		correctErrors(input, input.size + ecc.size, syndromes, errorLocatorPoly, errorLocations);
		return true;
	}

	/**
	 * Computes the syndromes for the message (input + ecc). If there's no error then the output will be zero.
	 *
	 * @param input Data portion of the message
	 * @param ecc ECC portion of the message
	 * @param syndromes (Output) results of the syndromes computations
	 */
	void computeSyndromes( DogArray_I16 input,
						   DogArray_I16 ecc,
						   DogArray_I16 syndromes ) {
		syndromes.resize(syndromeLength());
		for (int i = 0; i < syndromes.size; i++) {
			int val = generatorPower(i);
			int eval = math.polyEval(input, val);
			syndromes.data[i] = (short)math.polyEvalContinue(eval, ecc, val);
		}
	}

	/**
	 * Computes the error locator polynomial using  Berlekamp-Massey algorithm [1]
	 *
	 * <p>[1] Massey, J. L. (1969), "Shift-register synthesis and BCH decoding" (PDF), IEEE Trans.
	 * Information Theory, IT-15 (1): 122–127</p>
	 *
	 * @param syndromes (Input) The syndromes
	 * @param errorLocator (Output) Error locator polynomial. Coefficients are large to small.
	 */
	void findErrorLocatorPolynomialBM( DogArray_I16 syndromes, DogArray_I16 errorLocator ) {
		DogArray_I16 C = errorLocator; // error polynomial
		DogArray_I16 B = err_eval;  // previous error polynomial

		initToOne(C, syndromes.size + 1);
		initToOne(B, syndromes.size + 1);

		DogArray_I16 tmp = errorX;
		tmp.resize(syndromes.size);

//		int L = 0;
//		int m = 1; // stores how much B is 'shifted' by
		int b = 1;

		for (int n = 0; n < syndromes.size; n++) {

			// Compute discrepancy delta
			int delta = syndromes.data[n] & 0xFFFF;

			for (int j = 1; j < C.size; j++) {
				delta ^= math.multiply(C.data[C.size - j - 1] & 0xFFFF, syndromes.data[n - j] & 0xFFFF);
			}

			// B = D^m * B
			B.data[B.size++] = 0;

			// Step 3 is implicitly handled
			// m = m + 1

			if (delta != 0) {
				int scale = math.multiply(delta, math.inverse(b));
				math.polyAddScaleB(C, B, scale, tmp);

				if (B.size <= C.size) {
					// if 2*L > N ---- Step 4
//					m += 1;
				} else {
					// if 2*L <= N --- Step 5
					B.setTo(C);
//					L = n+1-L;
					b = delta;
//					m = 1;
				}
				C.setTo(tmp);
			}
		}

		removeLeadingZeros(C);
	}

	private void removeLeadingZeros( DogArray_I16 poly ) {
		int count = 0;
		for (; count < poly.size; count++) {
			if (poly.data[count] != 0)
				break;
		}
		for (int i = count; i < poly.size; i++) {
			poly.data[i - count] = poly.data[i];
		}
		poly.size -= count;
	}

	/**
	 * Compute the error locator polynomial when given the error locations in the message.
	 *
	 * @param messageLength (Input) Length of the message
	 * @param errorLocations (Input) List of error locations in the short
	 * @param errorLocator (Output) Error locator polynomial. Coefficients are large to small.
	 */
	void findErrorLocatorPolynomial( int messageLength, DogArray_I32 errorLocations, DogArray_I16 errorLocator ) {
		tmp1.resize(2);
		tmp1.data[1] = 1;
		errorLocator.resize(1);
		errorLocator.data[0] = 1;
		for (int i = 0; i < errorLocations.size; i++) {
			// Convert from positions in the message to coefficient degrees
			int where = messageLength - errorLocations.get(i) - 1;

			// tmp1 = [2**w,1]
			tmp1.data[0] = (short)math.power(2, where);
//			tmp1.data[1] = 1;

			tmp0.setTo(errorLocator);
			math.polyMult(tmp0, tmp1, errorLocator);
		}
	}

	/**
	 * Creates a list of shorts that have errors in them
	 *
	 * @param errorLocator (Input) Error locator polynomial. Coefficients from small to large.
	 * @param messageLength (Input) Length of the message + ecc.
	 * @param locations (Output) locations of shorts in message with errors.
	 */
	public boolean findErrorLocations_BruteForce( DogArray_I16 errorLocator,
												  int messageLength,
												  DogArray_I32 locations ) {
		locations.resize(0);
		for (int i = 0; i < messageLength; i++) {
			if (math.polyEval_S(errorLocator, math.power(2, i)) == 0) {
				locations.add(messageLength - i - 1);
			}
		}

		// see if the expected number of errors were found
		return locations.size == errorLocator.size - 1;
	}

	/**
	 * Use Forney algorithm to compute correction values.
	 *
	 * @param message (Input/Output) The message which is to be corrected. Just the message. ECC not required.
	 * @param length_msg_ecc (Input) length of message and ecc code
	 * @param errorLocations (Input) locations of shorts in message with errors.
	 */
	void correctErrors( DogArray_I16 message,
						int length_msg_ecc,
						DogArray_I16 syndromes,
						DogArray_I16 errorLocator,
						DogArray_I32 errorLocations ) {
		findErrorEvaluator(syndromes, errorLocator, err_eval);

		// Compute error positions
		errorX.resetResize(errorLocations.size, (short)0);
		for (int i = 0; i < errorLocations.size; i++) {
			int coef_pos = (length_msg_ecc - errorLocations.data[i] - 1);
			errorX.data[i] = (short)math.power(2, coef_pos);
			// The commented out code below replicates exactly how the reference code works. This code above
			// seems to work just as well and passes all the unit tests
//			int coef_pos = math.max_value - (length_msg_ecc - errorLocations.data[i] - 1);
//			errorX.data[i] = (short)math.power_n(2, -coef_pos);
		}

		err_loc_prime_tmp.resize(errorX.size);

		// storage for error magnitude polynomial
		for (int i = 0; i < errorX.size; i++) {
			int Xi = errorX.data[i] & 0xFFFF;
			int Xi_inv = math.inverse(Xi);

			// Compute the polynomial derivative
			err_loc_prime_tmp.size = 0;
			for (int j = 0; j < errorX.size; j++) {
				if (i == j)
					continue;
				err_loc_prime_tmp.data[err_loc_prime_tmp.size++] =
						(short)GaliosFieldOps.subtract(1, math.multiply(Xi_inv, errorX.data[j] & 0xFFFF));
			}
			// compute the product, which is the denominator of Forney algorithm (errata locator derivative)
			int err_loc_prime = 1;
			for (int j = 0; j < err_loc_prime_tmp.size; j++) {
				err_loc_prime = math.multiply(err_loc_prime, err_loc_prime_tmp.data[j] & 0xFFFF);
			}

			int y = math.polyEval_S(err_eval, Xi_inv);
			y = math.multiply(math.power(Xi, 1), y);

			// Compute the magnitude
			int magnitude = math.divide(y, err_loc_prime);
			if (generatorBase != 0) {
				magnitude = math.multiply(magnitude, Xi_inv);
			}

			// only apply a correction if it's part of the message and not the ECC
			int loc = errorLocations.get(i);
			if (loc < message.size)
				message.data[loc] = (short)((message.data[loc] & 0xFFFF) ^ magnitude);
		}
	}

	/**
	 * Compute the error evaluator polynomial Omega.
	 *
	 * @param syndromes (Input) syndromes
	 * @param errorLocator (Input) error locator polynomial.
	 * @param evaluator (Output) error evaluator polynomial. large to small coef
	 */
	void findErrorEvaluator( DogArray_I16 syndromes, DogArray_I16 errorLocator,
							 DogArray_I16 evaluator ) {
		math.polyMult_flipA(syndromes, errorLocator, evaluator);
		int N = errorLocator.size - 1;
		int offset = evaluator.size - N;
		for (int i = 0; i < N; i++) {
			evaluator.data[i] = evaluator.data[i + offset];
		}
		evaluator.data[N] = 0;
		evaluator.size = errorLocator.size;

		// flip evaluator around // TODO remove this flip and do it in place
		for (int i = 0; i < evaluator.size/2; i++) {
			int j = evaluator.size - i - 1;
			int tmp = evaluator.data[i];
			evaluator.data[i] = evaluator.data[j];
			evaluator.data[j] = (short)tmp;
		}
	}

	/**
	 * Creates the generator function with the specified polynomial degree. The generator function is composed
	 * of factors of (x-a_n) where a_n is a power of 2.<br>
	 *
	 * if generatorFamily = 0 then:<br>
	 * g<sub>4</sub>(x) = (x - α0) (x - α1) (x - α2) (x - α3) = 01 x4 + 0f x3 + 36 x2 + 78 x + 40
	 *
	 * @param degree Number of words in ECC. Larger values mean more error correction
	 */
	public void generator( int degree ) {
		// initialize to a polynomial = 1
		initToOne(generator, degree + 1);

		// (1*x - a[i])
		tmp1.resize(2);
		tmp1.data[0] = 1;
		for (int i = 0; i < degree; i++) {
			tmp1.data[1] = (short)generatorPower(i);
			math.polyMult(generator, tmp1, tmp0);
			generator.setTo(tmp0);
		}
	}

	void initToOne( DogArray_I16 poly, int length ) {
		poly.reset();
		poly.reserve(length);
		poly.size = 1;
		poly.data[0] = 1;
	}

	int generatorPower( int level ) {
		return math.power(2, level + generatorBase);
	}

	private int syndromeLength() {
		return generator.size - 1;
	}

	/** Number of bit errors */
	public int getTotalErrors() {
		return errorLocations.size;
	}
}
