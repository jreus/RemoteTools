
(
a = ();
a.autoRestart = true;
FileLog.logErrors(true, \monitor, {|ex| "A critical error occurred, do something! %".format(ex.errorString).warn });
a.log = FileLog(\monitor);
a.log.initLogFile("logs/logfile.log".resolveRelative.standardizePath);

a.cleanup = {|e|
	a.log.warning("Do Cleanup");
	Ndef.clear(0);
	CmdPeriod.run;
};

a.reboot = {|e|
	a.cleanup();
	{
		5.wait;
		a.log.warning("Kill All Server Processes");
		Server.killAll;
		6.wait;
		a.log.warning("Reboot Server");
		s.waitForBoot {
			a.log.info("Server successfully booted");
			a.run();
		};
	}.fork(AppClock);

};

// Main Program
a.run = {|e|
	a.log.info("Entering Main Program");

	f = FX.new(s);

	Ndef(\unittests, {|ksig=0|
		var sig, t_trig, channels, tmp;
		t_trig = Impulse.ar(0.1);
		sig = Saw.ar(Scale.major.ratios2(0, 9) * \d3.f * Lag.ar(TRand.ar(1.0, 10, t_trig).round(1.0), 0.5));
		sig = sig * LFNoise2.ar((1..9) * 0.1).range(0.0, 1.0) * Lag.ar(LFPulse.ar((1..9), width: 0.2).range(0.0, 1.0), 0.01);
		sig = Splay.ar(sig.scramble);


		// SILENCE
		// sig = sig * Env.linen(1.0, 5.0, 5.0).ar(0, 1.0);
		// MonitorSilence.ar(sig, maxSilence: 3.0, ampThresh: 0.00001, monitor: \default,
		// action: {|cause,vals,mon| "Silence Detected! %".format(mon).warn });
		// sig;

		// MONO SILENCE / DC
		// sig = (Mix.new(sig) * Env.linen(1.0, 5.0, 5.0).ar(0, 1.0));
		// MonitorSilence.ar(sig, maxSilence: 3.0, ampThresh: 0.00001, monitor: \default,
		// action: {|cause,vals,mon| "Mono Silence Detected! %".format(mon).warn });
		// sig;

		// CTRL RATE SILENCE
		// sig = Mix.new(sig) * Env.linen(1.0, 5.0, 5.0).ar(0, 1.0);
		// ksig = A2K.kr(sig);
		// MonitorSilence.kr(ksig, maxSilence: 3.0, ampThresh: 0.00001, monitor: \default,
		// action: {|cause,vals,mon| "KRate Silence Detected! %".format(mon).warn });
		// sig;

		// BAD VALUES
		// sig = sig / LFPulse.ar(0.1).range(1.0, 0.0);
		// MonitorBadValues.ar(sig, action: {|cause,vals,mon| "Bad values!".format(cause,vals).warn });
		// Sanitize.ar(sig);

		// MONO BAD VALUES
		// sig = Mix.new(sig) / LFPulse.ar(0.1).range(1.0, 0.0);
		// MonitorBadValues.ar(sig, action: {|cause,vals,mon| "Mono Bad values!".format(cause, vals).warn });
		// Sanitize.ar(sig);

		// CTRL RATE BAD VALUES
		// Uncomment these two lines to test for -+inf
		//sig = Mix.new(sig) / LFPulse.ar(0.1).range(1.0, 0.0);
		//ksig = A2K.kr(sig);
		// To test for NaN uncomment and run the line below.
		//Ndef(\monitorme).set(\ksig, -1.sqrt)
		// MonitorBadValues.kr(ksig, action: {|cause,vals,mon| "KRate Bad value!".format(cause,vals).warn });
		// Sanitize.ar(sig);

		// OUT OF BOUNDS
		// sig = sig * LFSaw.ar(0.1).range(0.5, 4.0);
		// MonitorBadValues.ar(sig, -1.0, 1.0, 0.5, \default, {|cause,vals,mon| "Out of bounds! % %".format(cause,vals).warn });
		// Limiter.ar(Sanitize.ar(sig));

		// MONO OUT OF BOUNDS
		// sig = Mix.ar(sig) * LFSaw.ar(0.1).range(0.5, 4.0);
		// MonitorBadValues.ar(sig, -1.0, 1.0, 0.5, \default, {|cause,vals,mon| "Mono Out of bounds! % %".format(cause,vals).warn });
		// Limiter.ar(Sanitize.ar(sig));

		// KRATE OUT OF BOUNDS
		// sig = Mix.ar(sig) * LFSaw.ar(0.1).range(0.5, 4.0);
		// ksig = A2K.kr(sig);
		// MonitorBadValues.kr(ksig, -1.0, 1.0, 0.5, \default, {|cause, vals, mon| "KRate Out of bounds! % %".format(cause,vals).warn });
		// Limiter.ar(Sanitize.ar(sig));


		// USE CASES >>

		//LOG BAD VALUES
		tmp = sig / LFPulse.ar(0.1).range(1.0, 0.0);
		MonitorBadValues.ar(tmp, name: "m_badValDiv", action: {|cause, vals, mon|
			a.log.error("Bad values detected in mon %, cause: % in vals: % with monitor: %".format(mon[\name], cause, vals, mon));
		});

		//LOG BAD VALUES + LANGUAGE PARAMS
		//To test for NaN uncomment and run the line below.
		//Ndef(\unittests).set(\ksig, -1.sqrt)
		MonitorBadValues.kr(ksig, name: "m_badvalKsig", action: {|cause, vals, mon|
			a.log.error("Bad value % in %! Values: %".format(cause, mon[\name], vals));
		});


		//REBOOT THE PATCH ON SILENCE & LOG
		sig = sig * Env.linen(1.0, 5.0, 5.0).ar(0, 1.0);
		MonitorSilence.ar(sig, name: "m_silenceEndChain", maxSilence: 15.0, ampThresh: 0.0001, monitor: \default,
			action: {|cause, vals, mon|
				a.log.critical("Silence % Detected for 15s at %, something must have blown up on the server!".format(cause, mon));
				a.reboot;
		});
		sig * 0.1;

	});

	f.unit(\mainchannel, 2, "unittests() rev()");
};

s.options.memSize = 2 ** 16;
s.waitForBoot {
	a.run();
};

);





