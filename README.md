# sms-recorder
Demonstration of how to record SMS on Android to a file and use retrofit to upload the file to a server.
Uses a BootReceiver launched Android Service and a Thread with a LinkedBlockingQueue to communicate with the SMS Content Listener.
Currently very "raw" and requires more commenting and a proper UI (if required)
NB: The original code was written pre-rxandroid so a TODO is to rewrite using more elegant reactive code


