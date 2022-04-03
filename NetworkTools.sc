/*
Usage:
e = SMTPEmailer(\myemailer, "smtp.gmail.com", 465, "username", "password");
e.send("sender@gmail.com", "recipient@gmail.com", "Subject - This is a test msg from SC", "Testing..1..2..3\n\nHello from SC!", {|exit, pid| "Completed with exit code %".format(exit).postln});
*/
SMTPEmailer : Singleton {

	classvar defaultFileName, defaultMessageFormat, defaultCommand, emailRegexp, defaultAction, <>debounceTime=1;
	var <>mail_protocol, <>smtp_server, <>smtp_port, <>smtp_user, <>smtp_password, <last_sent=0;


	*initClass {
		emailRegexp = "([A-Za-z\\.\ \\-]*)?[ ]?[<]?([A-Za-z0-9\\-\\.\\_]+@[A-Za-z\\-\\.]+)[>]?";
		defaultFileName = "tmp_scemailer_msg.txt";
		defaultMessageFormat = "From: %\nTo: %\nSubject: %\n\n%\n%";
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

	/* Send an email
	UNIX EXIT CODES
	https://www.cyberciti.biz/faq/linux-bash-exit-status-set-exit-statusin-bash/

	56 - invalid request
	*/
	send {| from, to, subject, msg, action |
		var text, cmd, msg_filepath, timestamp, now;

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
			timestamp = Date.getDate.asString;
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
			last_sent = now;
		} {
			"IGNORING EMAIL REQUEST! Too many emails sent in too little time!".warn;
		};
	}


}
