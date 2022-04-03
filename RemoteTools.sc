/*
RemoteTools

A collection of classes that I've found useful for monitoring, debugging and logging
a long-running, remote SuperCollider installation.

(C) 2022 J Chaim Reus
*/


SignalMonitor : Singleton {

	classvar <defaultAction;
	var <oscListener, <listenerId, <listenerAddress, <registrationId, <>server;
	var <liveMonitors, <idCount;

	*initClass {
		defaultAction = {|cause, vals, mon| "Received % Signal % from Monitor: %".format(cause, vals, mon).warn };
		CmdPeriod.add({ SignalMonitor.clearAll });
	}


	// Reset all Signal Monitors
	*clearAll {
		SignalMonitor.all.do {|sm|
			sm.cleanup;
		};
	}

	cleanup {
		oscListener.free;
		liveMonitors.clear;
		super.clear;
	}

	init {
		"CREATING SignalMonitor: %".format(this.name).warn;
		registrationId = 0;
		idCount = 0;
		liveMonitors = IdentityDictionary.new();
		listenerAddress = "/signalmonitor_%_addr".format(this.name);

		oscListener = OSCFunc.new({|msg|
			var mon, monid, nodeid, values;
			msg.postln;
			nodeid = msg[1].asInteger;
			monid = msg[2].asInteger;

			// If a registration message comes in, register that node as one containing a monitor...
			mon = liveMonitors.at(monid);
			if(mon.notNil) {
				var cause = mon[\type];
				// We got an alert!
				if(mon[\node].isNil) {
					mon[\node] = Node.basicNew(server, nodeid);
					mon[\node].register(true);
					mon[\status] = \registered;
					mon[\nodeid] = nodeid;
				};

				if(mon[\type] == \badvalues) { // refine cause for badvalues cases
					var badvals, oob;
					badvals = msg[3].asInteger;
					oob = msg[4].asInteger;
					values = msg[5..];
					if(oob > 0) {
						cause = \oob;
					} {
						switch(badvals,
							1, { cause = \NaN },
							2, { cause = \inf },
							3, { cause = \denormal },
							{ cause = \unknown }
						);
					};
				} {
					values = msg[3..];
				};

				if(mon[\action].notNil) {
					mon[\action].value(cause, values, mon);
				} {
					// Run default action...
					defaultAction.value(cause, values, mon);
				};

			} {
				// Monitor message from an unregistered monitor, use the default monitor action? Or...
				"Received message from an unregistered monitor with id: %".format(monid).error;
				mon = nil;
			};

			// Cleanup any dead monitors...
			liveMonitors.keys.do {|idx|
				var lm = liveMonitors[idx];
				if(lm[\node].notNil.and {lm[\node].isPlaying.not}) {
					"Removing Monitor %".format(idx).warn;
					liveMonitors.removeAt(idx);
				};
			};

		}, listenerAddress);
	}

	set {|serv|
		server = serv;
	}

	// type is \silence or \badvalues
	registerMonitor {|type, name, action|
		var mon;
		idCount = idCount + 1;
		if(name.isNil) { name = idCount };
		"Registered monitor %: %".format(idCount, name).warn;
		mon = Dictionary.newFrom([\id, idCount, \name, name, \type, type, \action, action, \status, \awaitingNodeId, \node, nil]);
		liveMonitors.put(idCount, mon);
		^idCount;
	}

}


// Pseudo Ugen, implements silence and DC monitoring with callback function if silence exceeds a certain duration
MonitorSilence {
	*ar {| in, maxSilence=10, ampThresh=0.001, monitor=\default, name=nil, action |
		var monAddr, monId, monregistrationId, mon = SignalMonitor(monitor);
		var t_silence, monoin;
		monAddr = mon.listenerAddress;
		monId = mon.registerMonitor(\silence, name, action);
		if(in.size > 1) { monoin = in.sum } { monoin = in };
		t_silence = DetectSilence.ar(LeakDC.ar(monoin), ampThresh, maxSilence);
		^SendReply.ar(t_silence, monAddr, in, monId);
	}

	*kr {| in, maxSilence=10, ampThresh=0.001, monitor=\default, name=nil, action |
		var monAddr, monId, monregistrationId, mon = SignalMonitor(monitor);
		var t_silence, monoin;
		monAddr = mon.listenerAddress;
		monId = mon.registerMonitor(\silence, name, action);
		if(in.size > 1) { monoin = in.sum } { monoin = in };
		t_silence = DetectSilence.kr(monoin, ampThresh, maxSilence);
		^SendReply.kr(t_silence, monAddr, in, monId);
	}
}

// Pseudo Ugen, implements badvalues and out-of-bounds monitoring with callback function if bad values detected
MonitorBadValues {

	*ar {| in, minValue=(-1.0), maxValue=1.0, resetFreq=0.1, monitor=\default, name=nil, action |
		var monAddr, monId, monregistrationId, mon = SignalMonitor(monitor);
		var safein, t_silence, silence, checkChange, badvals, t_badvals, t_outofbounds, t_reply, outofbounds, min, max, t_reset;
		monAddr = mon.listenerAddress;
		monId = mon.registerMonitor(\badvalues, name, action);

		badvals = CheckBadValues.ar(in, post: 0);
		t_badvals = (badvals > 0).sum;
		// TODO: this is a very crude way of doing this, and even will result in incorrect reporting
		// ideally we would want to get the maximum value of badvals
		//if(badvals.size > 1) { badvals = badvals.max }; // this unfortunately doesn't work
		if(badvals.size > 1) { badvals = NumChannels.ar(badvals, 1, false) };
		safein = Sanitize.ar(in);
		t_reset = Impulse.ar(resetFreq);
		min = RunningMin.ar(safein, t_reset);
		max = RunningMax.ar(safein, t_reset);
		outofbounds = (min < minValue) + (max > maxValue);
		if(outofbounds.size > 1) { outofbounds = outofbounds.sum };
		t_outofbounds = outofbounds;
		t_reply = t_badvals + t_outofbounds;
		^SendReply.ar(t_reply, monAddr, [badvals, outofbounds, in].flatten, monId);
	}

	*kr {| in, minValue=(-1.0), maxValue=1.0, resetFreq=0.1, monitor=\default, name=nil, action |
		var monAddr, monId, monregistrationId, mon = SignalMonitor(monitor);
		var safein, t_silence, silence, checkChange, badvals, t_badvals, t_outofbounds, outofbounds, min, max, t_reset;
		monAddr = mon.listenerAddress;
		monId = mon.registerMonitor(\badvalues, name, action);
		badvals = CheckBadValues.kr(in, post: 0);
		if(badvals.size > 1) { badvals = badvals.max };
		t_badvals = badvals > 0;
		safein = Sanitize.kr(in);
		t_reset = Impulse.kr(resetFreq);
		min = RunningMin.kr(safein, t_reset);
		max = RunningMax.kr(safein, t_reset);
		outofbounds = (min < minValue) + (max > maxValue);
		if(outofbounds.size > 1) { outofbounds = outofbounds.max };
		t_outofbounds = outofbounds;
		^SendReply.kr(t_badvals + t_outofbounds, monAddr, [badvals, outofbounds, in].flatten, monId);
	}
}

/*
USAGE:

FileLog.logErrors(true, \test);
l = FileLog(\test);
l.maxLength = 10; // don't waste a lot of memory of logging when everything will be written to file
l.initLogFile("logs/logfile.log".resolveRelative.standardizePath);
l.info("Oh % %", 1, 2);
l.critical("AH % %", 9, 21);
l.warning("ahhuy % %", 9, 21);
l.error("oyoy % %", 9, 21);

*/
FileLog : Singleton {

	classvar defaultFormatter, splitLineFormatter, onErrorAction, <levels, exceptionHandler;
	var <actions, <formatters, <actionsByLevel, <>shouldPost = true, <maxLength = 500;
	var <logfilePath, <fileLogAction, <postLogAction;

	var <lines, <level, levelNum, <splitLines=false, <>unprintedLine="";
	var <exceptionHistory, <>suppressRepeatExceptions=true, <>suppressRepeatsTimeThresh=10.0;


	*initClass {
		defaultFormatter = {|item, log|
			"[%] ".format(log.name.asString().toUpper()).padRight(12) ++ item[\string];
		};

		splitLineFormatter = {|item, log|
			var logOutput;

			log.unprintedLine = log.unprintedLine ++ item[\string];

			if (log.unprintedLine.contains("\n")) {
				log.unprintedLine = log.unprintedLine.split(Char.nl);
			};

			logOutput = log.unprintedLine[0..(log.unprintedLine.size - 2)].collect({
				|line|
				"[%] ".format(log.name.asString().toUpper()).padRight(12) ++ line;
			}).join("\n");

			log.unprintedLine = log.unprintedLine.last;

			logOutput
		};

		levels = (
			all: -999,
			debug: 0,
			info: 1,
			warning: 3,
			error: 6,
			critical: 9
		);
	}



	*logErrors {|shouldLog = true, loggerID=\default, errorAction=nil|
		var rootThread = thisThread, handler;

		while { rootThread.parent.notNil } {
			rootThread = rootThread.parent;
		};

		if (shouldLog) {
			var oldExceptionHandler = rootThread.exceptionHandler;

			exceptionHandler = {|exc|
				{
					var shouldSuppress=false, log = FileLog(loggerID);
					shouldSuppress = log.shouldSuppressException(exc);

					//"rootThread: Got exception % with % %".format(exc, log, shouldSuppress).warn;

					if(shouldSuppress) {
						// Suppress Exception Handling
						log.error("(Suppressed)" + exc.class + exc.errorString);
					} {
						// Report Exception
						log.error(exc.errorString);
						if(errorAction.notNil) {
							errorAction.value(exc);
						};

						rootThread.parent.handleError(exc);
					};

				}.try;
			};

			rootThread.exceptionHandler = exceptionHandler;

			// onErrorAction = {
			// 	FileLog(loggerID).critical("A Language Error was Encountered");
			// };
			//OnError.add(onErrorAction)

		} {
			if (rootThread.exceptionHandler == exceptionHandler) {
				rootThread.exceptionHandler = exceptionHandler = nil;
			};
		}
	}

	// Override this method to run initializations when a Singleton instance is created
	init {
		actions = IdentitySet();
		actionsByLevel = IdentityDictionary();
		formatters = Dictionary();
		lines = LinkedList(maxLength);
		exceptionHistory = IdentityDictionary();
		this.level = \info;
		formatters[\default] = splitLines.if({ splitLineFormatter }, { defaultFormatter });
		formatters[\post] = {|item, logger|
			var res;
			res = ":%: %".format(item[\time].stamp, item[\string]);

			switch(item[\level],
				\warning, {
					res = res;
				},
				\error, {
					res = res;
				},
				\critical, {
					res = "(%) %".format(item[\level].asString.toUpper, res);
				},
				{
					res = "%: %".format(item[\level].asString.toUpper, res);
				}
			);
			res;
		};

		postLogAction = {|logitem, logger|
			var fmt = logger.format(logitem, \post);
			switch(logitem[\level],
				\warning, { fmt.warn },
				\error, { fmt.error },
				\critical, { fmt.error },
				{ fmt.postln }
			);
		};

		// Add base handlers action..
		actions.add({|logitem, logger|
			var fmt = logger.format(logitem, \default);
			var act = actionsByLevel.at(logitem[\level]);
			if(act.notNil) {
				act.value(logitem[\string], logitem[\time].asString);
			};
		});

	}

	initLogFile {|logsPath, addTimeStamp=true|
		// Create any necessary directory paths
		// & create log file, prepare for writing
		logsPath = PathName(logsPath);

		if(File.exists(logsPath.pathOnly).not) {
			File.mkdir(logsPath.pathOnly);
		};

		logfilePath = logsPath.pathOnly +/+ "%-%.%".format(logsPath.fileNameWithoutExtension, Date.getDate.stamp, logsPath.extension);

		formatters[\file] = {|item, logger|
			"% %: %".format(item[\time].stamp, item[\level].asString.toUpper, item[\string]);
		};

		fileLogAction = {|logitem, logger|
			File.use(logfilePath, "a", {|fp|
				// FORMAT AND THEN WRITE: .format(l.formatter.value(item, log))
				fp.write(logger.format(logitem, \file) ++ "\n");
			});
		};


	}

	// Returns true if an exception should be suppressed
	shouldSuppressException {|exc|
		var res = false, hist, now = Process.elapsedTime;
		hist = exceptionHistory.at(exc.class);
		if(hist.isNil) {
			hist = (
				lastEncountered: 0,
				numEncountered: 0
			);
			exceptionHistory.put(exc.class, hist);
		};

		//"FileLog: Got % with history %".format(exc, hist).warn;

		if(suppressRepeatExceptions) {
			// This is considered a repeat and should be suppressed.
			res = (now - hist.lastEncountered) < suppressRepeatsTimeThresh;
		};

		hist.lastEncountered = now;
		hist.numEncountered = hist.numEncountered + 1;

		^res;
	}

	splitLines_{|value|
		splitLines = value;
		if (splitLines and: { formatters[\default] == defaultFormatter }) {
			formatters[\default] = splitLineFormatter
		};
		if (splitLines.not and: { formatters[\default] == splitLineFormatter }) {
			formatters[\default] = defaultFormatter;
		}
	}

	maxLength_ {|newval|
		maxLength = newval;
		lines = LinkedList(maxLength);
	}

	level_{|inLevel|
		level = inLevel;
		levelNum = levels[level];
	}

	addEntry {| item |
		lines.add(item);
		if (lines.size() > maxLength) {
			lines.popFirst();
		}
	}

	debug {| str ...items |
		this.set(str.asString.format(*items), \debug)
	}

	info {| str ...items |
		this.set(str.asString.format(*items), \info)
	}

	warning {| str ...items |
		this.set(str.asString.format(*items), \warning)
	}

	error {| str ...items |
		this.set(str.asString.format(*items), \error)
	}

	critical {| str ...items |
		this.set(str.asString.format(*items), \critical)
	}

	log {| str, level |
		this.set(str, level)
	}

	// Override this to receive 'settings' parameter from Singleton.new(name, settings)
	set {| str, inLevel = \default |
		var logLevel, logItem;
		logLevel = levels[inLevel] ? 0;
		if (logLevel >= levelNum) {
			logItem = (
				\string: str,
				\level: inLevel,
				\time: Date.getDate()
			);


			//logItem[\formatted] = this.format(logItem);

			this.addEntry(logItem);

			if (shouldPost) {
				postLogAction.value(logItem, this);
			};

			if (fileLogAction.notNil) {
				fileLogAction.value(logItem, this);
			};

			actions.do({| action |
				action.value(logItem, this);
			});
		}
	}

	format {| item, formatter=\default |
		^formatters[formatter].value(item, this);
	}

	// Convenience method to add additional actions to logging
	addHandler {|level, action|
		if(action.isNil) {
			actionsByLevel.removeAt(level.asSymbol);

		} {
			actionsByLevel.put(level.asSymbol, action);
		};
	}
}
