<?php
 
    define('MULTIPART_NAME','smslog');
    error_reporting(E_ALL ^ E_WARNING);  
    $filename = "SMS_FILE";

    if ( $_FILES[MULTIPART_NAME]["error"] > 0 ){
        $xml = simplexml_load_file('errorResponse.xml');
        $xml->{'response-status'}->code = 430;
        $xml->{'response-status'}->message = "internal error code: ".$_FILES[MULTIPART_NAME]["error"];
        echo $xml->asXML();
        exit( 1 );
    }
 
  /*
   * create a daily storage directory
   * Returns a warning second and subsequent creations of the day
   * TODO could we program around this warning as it goes into the return string to the client
   */ 
    date_default_timezone_set('UTC');
    $filename = $filename.'/'.date("Y").'/'.date("m").'/'.date("d");
    mkdir($filename, 0777, true);

  /* 
   * generate a UTC date time timestamp in the same format as the client : YYYYMMDDTHHMMSSZ
   * report_{server_time}_{client_time}_{userid}_{random}.csv
   * report_20140416T160152Z_20140416T155049Z_106064851_1410103821.csv
   */
    $server_time = date("Ymd") . "T". date("His") . "Z";
    $filename = $filename . "/smslog_" . $server_time . ".csv";
  
    move_uploaded_file($_FILES[MULTIPART_NAME]["tmp_name"], $filename);
	// Send back succeeded json but put a message in there
    echo ( "{\"message\":\"Good to go\",\"succeeded\":true}");
