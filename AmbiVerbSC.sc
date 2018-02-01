/*
<AmbiVerbSC - James Wenlock>
Center for Digital Arts and Experimental Media, University of Washington - https://dxarts.washington.edu/

   Copyright (C) <2017>  <James Wenlock>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

AmbiVerbSC {
    classvar <>dataDir;

    *setDataDir {
        block { |break|
            // look in downloaded quarks
            [
                Platform.userAppSupportDir +/+ "downloaded-quarks",
                Platform.systemAppSupportDir +/+ "downloaded-quarks",
                Platform.userExtensionDir,
                Platform.systemExtensionDir
            ].do{ |suppDir|
                var test = suppDir +/+ "AmbiVerbSC/Data";
                if (File.exists(test)) {
                    dataDir = test;
                    break.()
                };
            };
        };
        dataDir ?? {
            "AmbiVerbSC: couldn't find Data directory in Extensions or downloaded-quarks directory".throw;
        }
    }

	*ar {
		arg in, mix = 1, preDelay = 0, crossoverFreq = 3000,
		lowRT = 10, highRT = 7, dispersion = 1, size = "cubeS",
		modWidth = 0.2, modRate = 0.3, coupRate = 0.5,
		coupAmt = 6pi, phaseRotRate = 0.4, phaseRotAmt = 2pi,
		orientation  = \flu, maxPreDelay = 10, feedbackSpread = 1;

		var dry, wet, out;
		var allPassData1, allPassData2;
		var maxDelay, delay, delaySum;
		var localBus;
		var g, low, high, lowG, highG;
	    var dTs, decTs;
	  	var sum;
		var newLFMod, hilbert, hilbertAmt;
		var width;
		var maxFeedbackDelay, feedbackDelay;
		var modes;
		var hPFreq;
		var dTBag, decTBag;
		var apTwoLength;
		var spreadRange, widthRange;
		var phaseRotVar, coupRateVar;
		var phaseRotRates, coupRates, coupMod;
		var lagMul;

        // init data directory
        dataDir ?? {this.setDataDir};

		// Defines lag multiplier for LFNoises
		lagMul = (2 / (1 + sqrt(5))).reciprocal * 1.5;

		// # of allpasses in second cascade
		apTwoLength = 2;

		// g value = 1 /  golden ratio
		g = 2 / (1 + sqrt(5));

		// Highpass filter frequency
		hPFreq = 20;

		// Min and max of feedback spread scaler
		spreadRange = [0.2, 1];

		// Min and max of width scaler
		widthRange = [0.01, 0.7];

		// Defines rate of coupling
		coupRateVar = [0.003, 0.0214];
		coupRates = coupRate + {rrand(coupRateVar[0],coupRateVar[1])}!3;

		// Defines rate of rotation
		phaseRotVar = [0.003, 0.0214];
		phaseRotRates = phaseRotRate + {rrand(phaseRotVar[0], phaseRotVar[1])}!4;

		// Reads delay times from Data folder
        dTs =  Object.readArchive(dataDir +/+ format("DelayTimes/%.txt", size));

		// Calculates decay times
		decTs = -3 * dTs / (log10(g * dispersion));

		// Sums delays for feedback delay calculations
		dTs.flop.do({arg theseDts;
		   delaySum = delaySum.add(theseDts.sum);
		});

		// Calculates feedback delay time
		maxFeedbackDelay = delaySum + dTs[0] - ControlDur.ir;
		feedbackDelay = maxFeedbackDelay * feedbackSpread.linlin(0, 1, spreadRange[0], spreadRange[1]);

		// Calculates g values for low and high shelf filters
		lowG  = 10**(-3 * (feedbackDelay) / lowRT);
		highG = 10**(-3 * (feedbackDelay) / highRT);

		// Gets data from delay and decay times for first allpass cascade
		allPassData1 = [dTs, decTs].flop;

		// // Collects unique randomly selected delay and decay times from allPassCascade1 for second allpass cascade
		allPassData2 = 3.collect({arg i;
			allPassData1[i];
		});

		// Decodes B-format signal, sets dry value to initial signal
		dry = FoaDecode.ar(in, FoaDecoderMatrix.newBtoA(orientation));

		// Sums dry signal with feedback from allpass chain
		sum =  dry + LocalIn.ar(4);

		// First allpass
		allPassData1.do({arg thisData;
			var width = thisData[0] * modWidth.linlin(0, 1, widthRange[0], widthRange[1]) * 0.5;
			var maxDelay = thisData[0] * 2;
			var delay = thisData[0] + (LFDNoise3.ar(modRate)* width);
			sum = AllpassL.ar(sum, maxDelay, delay, thisData[1])
		});

		// Delay for feedback
		wet = DelayL.ar(sum, maxFeedbackDelay, feedbackDelay);

		// High pass to prevent DC Build-up
		wet = HPF.ar(wet, hPFreq);

		// Creates and scales low and high shelf by specified g-value
		low = LPF.ar(wet, crossoverFreq);
		high = low * -1 + wet;
		low = low * lowG;
		high = high * highG;
		wet = low + high;

		// Convert's signal to B-Format
		wet = FoaEncode.ar(wet, FoaEncoderMatrix.newAtoB);

		// Applies coupling in B-format with RTT
		coupMod = LFDNoise3.ar(coupRates) * coupAmt;
		wet = FoaRTT.ar(wet, coupMod[0], coupMod[1], coupMod[2]);

		// Applies hilbert phase rotation
		newLFMod = LFNoise2.ar(phaseRotRates) * phaseRotAmt;
		hilbert = wet;
		hilbert.collectInPlace({arg item, i;
			item = (Hilbert.ar(item) * [newLFMod[i].cos, newLFMod[i].sin]).sum;
		});
		wet = hilbert;

		wet = FoaDecode.ar(wet, FoaDecoderMatrix.newBtoA);

		// Sends signal back through loop
		LocalOut.ar(wet);

		// Delay to compensate for block size
		wet = DelayN.ar(wet, ControlRate.ir.reciprocal, ControlRate.ir.reciprocal);

		// Second allpass cascade
		allPassData2.do({arg thisData;
			var width = (thisData[0] * modWidth.linlin(0, 1, widthRange[0], widthRange[1])) * 0.5;
			var maxDelay = thisData[0] * 2;
			var delay = thisData[0] + (LFDNoise3.ar(modRate) * width);
			wet = AllpassL.ar(wet, maxDelay, delay,  thisData[1])
		});

		// Pre-delay
		wet = DelayN.ar(wet, maxPreDelay, preDelay);

		// Equal power mixer
		out = (dry * cos(mix*pi/2)) + (wet * sin(mix*pi/2));

		// Encodes to B-format
		out = FoaEncode.ar(out, FoaEncoderMatrix.newAtoB);

	^out;
    }
}
