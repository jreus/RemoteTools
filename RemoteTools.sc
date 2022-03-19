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
		defaultAction = {|monitorId, nodeId| "Received Monitor Notification from Monitor: %  Node: %".format(monitorId, nodeId).warn };
	}

	init {
		"CREATING SignalMonitor: %".format(this.name).warn;
		registrationId = 0;
		idCount = 0;
		liveMonitors = Dictionary.new();
		listenerId = "signalmonitor_%_listener".format(this.name).asSymbol;
		listenerAddress = "/signalmonitor_%_addr".format(this.name);

		oscListener = OSCdef(listenerId, {|msg|
			var mon, monid, nodeid;
			msg.postln;
			nodeid = msg[1].asInteger;
			monid = msg[2].asInteger;

			// If a registration message comes in, register that node as one containing a monitor...
			mon = liveMonitors.at(monid);
			if(mon.notNil) {
				// We got an alert!
				if(mon[\node].isNil) {
					mon[\node] = Node.basicNew(server, nodeid);
					mon[\node].register(true);
					mon[\status] = \registered;
				};

				if(mon[\action].notNil) {
					mon[\action].value(monid, nodeid);
				} {
					// Run default action...
					defaultAction.value(monid, nodeid);
				};

			} {
				// Monitor message from an unregistered monitor, use the default monitor action? Or...
				"Received message from an unregistered monitor %".format(monid).error;
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
	registerMonitor {|type, action|
		var mon;
		idCount = idCount + 1;
		"Registered monitor %".format(idCount).warn;
		mon = Dictionary.newFrom([\id, idCount, \type, type, \action, action, \status, \awaitingNodeId, \node, nil]);
		liveMonitors.put(idCount, mon);
		^idCount;
	}

}


// Pseudo Ugen, implements silence and DC monitoring with callback function if silence exceeds a certain duration
MonitorSilence {
	*kr {| in, maxSilence=120, ampThresh=0.001, monitor=\default, action |
		var monAddr, monId, monregistrationId, mon = SignalMonitor(monitor);
		var t_silence, monoin;
		monAddr = mon.listenerAddress;
		monId = mon.registerMonitor(\silence, action);
		if(in.size > 1) { monoin = in.sum } { monoin = in };
		t_silence = DetectSilence.ar(LeakDC.ar(monoin), ampThresh, maxSilence);
		^SendReply.kr(T2K.kr(t_silence), monAddr, A2K.kr(in), monId);
	}

	*ar {}
}

// Pseudo Ugen, implements badvalues and out-of-bounds monitoring with callback function if bad values detected
MonitorBadValues {
	*ar {| in, minValue=(-1.0), maxValue=1.0, resetFreq=0.1, monitor=\default, action |
		var monAddr, monId, monregistrationId, mon = SignalMonitor(monitor);
		var safein, t_silence, silence, checkChange, badvals, t_badvals, t_outofbounds, outofbounds, min, max, t_reset;
		monAddr = mon.listenerAddress;
		monId = mon.registerMonitor(\badvalues, action);

		badvals = CheckBadValues.ar(in, post: 0);
		t_badvals = badvals.sum > 0;
		safein = Sanitize.ar(in);
		t_reset = Impulse.ar(resetFreq);
		min = RunningMin.ar(safein, t_reset);
		max = RunningMax.ar(safein, t_reset);
		outofbounds = (min < minValue) + (max > maxValue);
		//outofbounds = 1 - InRange.ar(in * BinaryOpUGen('==', badvals, 0), minValue, maxValue);
		t_outofbounds = outofbounds.sum;

		// t_tick = Impulse.ar(1);
		// ts = Sweep.ar;
		// sigoob = 1.0 - InRange.ar(sig3, -1.0, 1.0);
		// t_reset = Impulse.ar(0.5);
	// for a stereo signal
	//peak = Peak.ar(sig3 * (1 - t_reset), t_reset).reduce(\max);
		// peak = Peak.ar(sig3 * (1 - t_reset), t_reset);
		// delaypeak = Delay1.ar(peak);
		// t_outofbounds = delaypeak > lim;

		^SendReply.ar(t_badvals + t_outofbounds, monAddr, [badvals[0], badvals[1], outofbounds[0], outofbounds[1], in[0], in[1]], monId);
	}

	*kr {}
}


// Pseudo Ugens
// ReplaceBadValues {
// 	*ar { |in, sub = 0, id = 0,  post = 2|
// 		var subIndex =  CheckBadValues.ar(in, id, post) > 0;
// 		// prepare for Select
// 		sub = sub.asArray.collect { |sub1|
// 			if (sub1.rate != \audio) { sub = K2A.ar(sub) } { sub };
// 		};
// 		^Select.ar(subIndex, [in, sub]);
// 	}
// 	*kr { |in, sub = 0, id = 0,  post = 2|
// 		var subIndex = CheckBadValues.kr(in, id, post) > 0;
// 		^Select.kr(subIndex, [in, sub]);
// 	}
// }


FileLog : Singleton {

	classvar defaultFormatter, splitLineFormatter, onErrorAction, <levels, exceptionHandler;
	var <>actions, <formatters, <>shouldPost = true, <>maxLength = 500;
	var <logfilePath, <fileLogAction, <postLogAction;

	var <lines, <level, levelNum, <splitLines=false, <>unprintedLine="";

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

	*logErrors {|shouldLog = true, loggerID=\default|
		var rootThread = thisThread, handler;

		while { rootThread.parent.notNil } {
			rootThread = rootThread.parent;
		};

		if (shouldLog) {
			exceptionHandler = {|exc|
				try {
					FileLog(loggerID).error(exc.errorString.replace("ERROR: ", ""));
				};

				rootThread.parent.handleError(exc);
			};

			rootThread.exceptionHandler = exceptionHandler;

			OnError.add(onErrorAction = {
				FileLog(loggerID, "---");
			})
		} {
			if (rootThread.exceptionHandler == exceptionHandler) {
				rootThread.exceptionHandler = exceptionHandler = nil;
			}
		}
	}

	// Override this method to run initializations when a Singleton instance is created
	init {
		actions = IdentitySet();
		formatters = Dictionary();
		lines = LinkedList(maxLength);
		this.level = \info;
		formatters[\default] = splitLines.if({ splitLineFormatter }, { defaultFormatter });
		formatters[\post] = {|item, logger|
			var res;
			switch(item[\level],
				\warning, {
					res = "%".format(item[\string]);
				},
				\error, {
					res = "%".format(item[\string]);
				},
				{
					res = "%::% %".format(item[\level].asString.toUpper, item[\string]);
				}
			);
			res;
		};

		postLogAction = {|logitem, logger|
			var fmt = logger.format(logitem, \post);
			switch(logitem[\level],
				\warning, { fmt.warn },
				\error, { fmt.error },
				{ fmt.postln }
			);
		};

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


	splitLines_{|value|
		splitLines = value;
		if (splitLines and: { formatters[\default] == defaultFormatter }) {
			formatters[\default] = splitLineFormatter
		};
		if (splitLines.not and: { formatters[\default] == splitLineFormatter }) {
			formatters[\default] = defaultFormatter;
		}
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
}
