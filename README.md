# sms-recorder
Based on an SMS listener daemon I wrote as a PoC for a client some years ago. Saves SMS content to a file for upload to a server.
A BootReceiver launched Android Service has a Thread which communicates with the SMS Content Listener by consuming a LinkedBlockingQueue produced on by the SMS Content Lstener.
Currently very "raw" - The original code was written pre-rxandroid so a TODO is to rewrite using more elegant reactive code
