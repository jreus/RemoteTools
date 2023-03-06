/*
Used to use curl to send an email, now uses the more reliable & robust s-nail
Requires s-nail & urlencode on unix systems!

Usage:
e = SMTPEmailer(\myemailer, "smtp.gmail.com", 465, "username", "password");
e.send("sender@gmail.com", "recipient@gmail.com", "Subject - This is a test msg from SC", "Testing..1..2..3\n\nHello from SC!", {|exit, pid| "Completed with exit code %".format(exit).postln});
*/

MailError : Error {}

SMTPEmailer : Singleton {

	classvar defaultFileName, defaultMessageFormat, defaultCommand, emailRegexp, defaultAction, <>debounceTime=1;
	var <>mail_protocol, <>smtp_server, <>smtp_port, <>smtp_user, <>smtp_password, <last_sent=0;

	// Meta parameters
	var <>emailSubjectPrefix;
	var <>maxEmails = 100; // Maximum number of emails to be sent by this SMTPEmailer
	var <sentEmails = 0; // Number of emails sent thusfar


	*initClass {
		emailRegexp = "([A-Za-z\\.\ \\-]*)?[ ]?[<]?([A-Za-z0-9\\-\\.\\_]+@[A-Za-z\\-\\.]+)[>]?";
		defaultFileName = "tmp_scemailer_msg.txt";
		defaultMessageFormat = "From: %\nTo: %\nSubject: %\n\n%\n%";

		// OLD CURL COMMAND - TODO: See if I can get this to work again!
		defaultCommand = "curl --url '%://%:%' --ssl-reqd --mail-from '%' --mail-rcpt '%' --upload-file % --user '%:%'";

		defaultAction = {|exitcode, pid|
			if(exitcode == 0) {
				"Email Sent Successfully".postln;
			} {
				MethodError("Could Not Send Email, curl received EXITCODE: %   PID: %".format(exitcode, pid)).throw;
			};
		};
	}

	// Override to initialize new SMTPEmailer
	init {

	}

	// Override this to receive 'settings' parameter from Singleton.new(name, settings)
	set {| server = nil, port = 465, user = nil, password = nil, protocol = "smtps" |

		"Initializing Emailer with server: %:%  and user: %:%  with protocol: %".format(server, port, user, password, protocol).warn;

		if( server.isNil || user.isNil || password.isNil ) {
			MethodError("Server, Username and Password must be specified when instantiating an Emailer").throw;
		};

		smtp_server=server;
		smtp_port=port;
		smtp_user=user;
		smtp_password=password;
		mail_protocol=protocol;
	}

	/* Send an email base function
	UNIX EXIT CODES
	https://www.cyberciti.biz/faq/linux-bash-exit-status-set-exit-statusin-bash/

	56 - invalid request
	*/
	send {| from, to, subject, msg, action |
		var text, cmd, msg_filepath, timestamp, now;
		// using the new s-nail approach.. which seems to be more reliable
		var sendCommand = "echo -e \"%\" | s-nail -v -r '%' -s \"%\" --set=v15-compat --set=mta=\"%\" '%'";

		if( from.isNil || to.isNil || subject.isNil || msg.isNil ) {
			MethodError("From, To, Subject and Message fields must be valid strings.").throw;
		};

		if( emailRegexp.matchRegexp(from).not || emailRegexp.matchRegexp(to).not ) {
			MethodError("From and To must be valid email addresses.").throw;
		};

		if(action.isNil) {
			action = defaultAction;
		};

		now = Process.elapsedTime;

		if((now - last_sent) > debounceTime) {
			var fullserver = "smtp://`urlencode %`:`urlencode %`@%:%".format(smtp_user, smtp_password, smtp_server, smtp_port);
			timestamp = Date.getDate.asString;
			// NEW S-NAIL WAY
			cmd = sendCommand.format(msg.escapeChar($"), from, subject.escapeChar($"), fullserver, to);
			cmd.postln;
			cmd.unixCmd({|exitcode, pid| action.value(exitcode, pid) });

			/*
			OLD CURL WAY
			text = defaultMessageFormat.format(from, to, subject, timestamp, msg);
			msg_filepath = defaultFileName.resolveRelative.standardizePath;
			cmd = defaultCommand.format(mail_protocol, smtp_server, smtp_port, from, to, msg_filepath, smtp_user, smtp_password);
			File.use(msg_filepath, "w", {|fp|
				fp.write(text);
			});
			cmd.postln;
			cmd.unixCmd({|exitcode, pid|
				action.value(exitcode, pid);
				File.delete(msg_filepath);
			});
			*/


			last_sent = now;
		} {
			"IGNORING EMAIL REQUEST! Too many emails sent in too little time!".warn;
		};
	}

	/*
	High level send function that keeps track of messages sent by this SMTPEmailer
	and adds a subject prefix. Things I have found useful in remote headless application settings.

	subject  The subject of the message, sans any subject prefix stored in this.emailSubjectPrefix
	message  The message to send
	incrementCount By how many messages should this increment the internally stored record of sent messages
	               usually 1, 0 if you don't want this message to count against the maximum number of messages
	force If true, ignores email count and sends no matter what.
	*/
	sendMail {|from, to, subject, message, incrementCount=1, force=false|
		warn("Sending % of maximum % emails this session".format(sentEmails + 1, maxEmails));
		if( force.or { sentEmails < maxEmails } ) {
			if(sentEmails == (maxEmails - 1)) {
				subject = "((FINAL EMAIL))" + subject;
			};

			if(emailSubjectPrefix.notNil) {
				subject = "%: %".format(emailSubjectPrefix, subject);
			};
			this.send(
				from: from,
				to: to,
				subject: subject,
				msg: message,
				action: {|exit, pid|
					if(exit != 0) {
						var errmsg = "Error sending email! Got exitcode: % and pid: % from s-nail".format(exit, pid);
						error(errmsg);
						MailError(errmsg).throw;
					} {
						warn("Sent email with exitcode: % and pid: %".format(exit, pid));
					};
			});

			sentEmails = sentEmails+incrementCount;
		} {
			MailError("Reached Maximum Emails Sent! Ignoring future calls to sendMail").throw;
		};
	}

	// reset number of sent emails
	resetSentCount { sentEmails = 0; }

}