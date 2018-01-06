var alarmPiData = {};  // global object, holds all data from AlarmPi

function loadData() {
    'use strict';
    
    clearData();
    var request = new XMLHttpRequest();
    
    // load all data from AlarmPi
    var hostName;
    if(location.hostname.length==0) {
        hostName = "127.0.0.1";
    }
    else {
        hostName = location.hostname;
    }
    request.open("GET", "http://"+hostName+":3948/");
    
    request.addEventListener('load', function (event) {
        
        if (request.status >= 200 && request.status < 300) {
            console.info("GET request successfully processed, status=" + request.status);
        } else {
            alert("Fehler bei Kommunikation mit ALarmPi: " + request.statusText);
        }
        
        alarmPiData = JSON.parse(request.responseText);
        console.info("received AlarmPi data: ");
        console.info(JSON.stringify(alarmPiData));
        
        $('#name').replaceWith(alarmPiData.name);
        updateAlarms();
    });
    
    request.addEventListener('error', function (event) {
        alert("Verbindung zu AlarmPi gescheitert. Fehlercode: " + request.statusText);
    });
    
    request.send();
}

function clearData() {
    'use strict';
    
    var alarmTableBody = $('#alarmTableBody');
    
    // remove all existing alarm rows
    var alarmRows = alarmTableBody.children();
    for(var row=0 ; row<alarmRows.length ; row++) {
        alarmRows[row].remove();
    }
}

function updateAlarms() {
    'use strict';
    
    var alarmTableBody = $('#alarmTableBody');
    
    // remove all existing alarm rows
    var alarmRows = alarmTableBody.children();
    for(var row=0 ; row<alarmRows.length ; row++) {
        alarmRows[row].remove();
    }
    
    for( var i=0 ; i<alarmPiData.alarms.length ; i++) {
        var alarm = alarmPiData.alarms[i];
        console.info("creating row for alarm index="+i+" id=" + alarm.id);
        alarmTableBody.append('<tr id="alarm_'+i+'"/>');
        var alarmRow = alarmTableBody.children('#alarm_'+i);
        alarmRow.append('<td><input type="checkbox" class="alarm_enabled">');
        alarmRow.append('<td><input type="time" contenteditable="true" class="alarm_time">');
        alarmRow.append('<td><input type="checkbox" class="alarm_oneTimeOnly">');
        alarmRow.append('<td><input type="checkbox" class="alarm_skipOnce">');
        alarmRow.append('<td><input type="checkbox" class="alarm_monday" value="MONDAY">');
        alarmRow.append('<td><input type="checkbox" class="alarm_tuesday" value="TUESDAY">');
        alarmRow.append('<td><input type="checkbox" class="alarm_wednesday" value="WEDNESDAY">');
        alarmRow.append('<td><input type="checkbox" class="alarm_thursday" value="THURSDAY">');
        alarmRow.append('<td><input type="checkbox" class="alarm_friday" value="FRIDAY">');
        alarmRow.append('<td><input type="checkbox" class="alarm_saturday" value="SATURDAY">');
        alarmRow.append('<td><input type="checkbox" class="alarm_sunday" value="SUNDAY">');
        
        alarmRow.children().children('.alarm_enabled').attr('checked',alarm.enabled).click(handleAlarmModification);
        alarmRow.children().children('.alarm_time').attr('value',alarm.time).change(handleAlarmModification);
        alarmRow.children().children('.alarm_oneTimeOnly').attr('checked',alarm.oneTimeOnly).click(handleAlarmModification);
        alarmRow.children().children('.alarm_skipOnce').attr('checked',alarm.skipOnce).click(handleAlarmModification);
        alarmRow.children().children('.alarm_monday').attr('checked',alarm.weekDays.indexOf('MONDAY')>=0).click(handleAlarmModification);
        alarmRow.children().children('.alarm_tuesday').attr('checked',alarm.weekDays.indexOf('TUESDAY')>=0).click(handleAlarmModification);
        alarmRow.children().children('.alarm_wednesday').attr('checked',alarm.weekDays.indexOf('WEDNESDAY')>=0).click(handleAlarmModification);
        alarmRow.children().children('.alarm_thursday').attr('checked',alarm.weekDays.indexOf('THURSDAY')>=0).click(handleAlarmModification);
        alarmRow.children().children('.alarm_friday').attr('checked',alarm.weekDays.indexOf('FRIDAY')>=0).click(handleAlarmModification);
        alarmRow.children().children('.alarm_saturday').attr('checked',alarm.weekDays.indexOf('SATURDAY')>=0).click(handleAlarmModification);
        alarmRow.children().children('.alarm_sunday').attr('checked',alarm.weekDays.indexOf('SUNDAY')>=0).click(handleAlarmModification);
        
        $('#alarmsSubmit').attr('disabled',true);
    }
}

function handleAlarmModification(event) {
    'use strict';
    
    var id = event.target.parentElement.parentElement.getAttribute('id');
    var alarmId = id.substring(id.indexOf('_')+1);
    
    console.info('marking alarm index '+alarmId+', id='+alarmPiData.alarms[alarmId].id+' as modified');
    alarmPiData.alarms[alarmId].modified = true;
    
    $('#alarmsSubmit').attr('disabled',false);
}

function submitAlarms() {
    'use strict';
    var alarmTableBody = $('#alarmTableBody');
    
    var submissionData = {};
    submissionData.alarms = [];
    
    for( var i=0 ; i<alarmPiData.alarms.length ; i++) {
        var alarm = alarmPiData.alarms[i];
        if(alarm.modified) {
            console.info('processing alarm for submit: index=' + i+' id='+alarm.id);
            var alarmRow = alarmTableBody.children('#alarm_'+i);
            
            alarm.enabled     = alarmRow.children().children('.alarm_enabled').prop('checked');
            alarm.time        = alarmRow.children().children('.alarm_time').val();
            alarm.oneTimeOnly = alarmRow.children().children('.alarm_oneTimeOnly').prop('checked');
            alarm.skipOnce    = alarmRow.children().children('.alarm_skipOnce').prop('checked');
            
            var weekDaysString = '[';
            var hasDays = false;
            var weekDays = ["monday","tuesday","wednesday","thursday","friday","saturday","sunday"]
            
            for(var day=0 ; day<weekDays.length ; day++) {
                if(alarmRow.children().children('.alarm_'+weekDays[day]).prop('checked')) {
                    if(hasDays) {
                        weekDaysString += ',';
                    }
                    weekDaysString += alarmRow.children().children('.alarm_'+weekDays[day]).val();
                    hasDays = true;
                }
            }
                    
            weekDaysString += ']';
            alarm.weekDays = weekDaysString;           
            
            submissionData.alarms.push(alarm);
        }
    }
    
    console.info("submitting AlarmPi data: ");
    console.info(JSON.stringify(submissionData));
    
    var request = new XMLHttpRequest();
    
    // load all data from AlarmPi
	var hostName;
    if(location.hostname.length==0) {
        hostName = "127.0.0.1";
    }
    else {
        hostName = location.hostname;
    }
    request.open("POST", "http://"+hostName+":3948/ "+JSON.stringify(submissionData));
    
    request.addEventListener('load', function (event) {
        
        if (request.status >= 200 && request.status < 300) {
            console.info("POST request successfully processed, status=" + request.status);
        } else {
            alert("Fehler bei Kommunikation mit AlarmPi: " + request.statusText);
        }
        
        console.info("received AlarmPi data: "+request.responseText);
    });
    
    request.addEventListener('error', function (event) {
        alert("Verbindung zu AlarmPi gescheitert. Fehlercode: " + request.statusText);
    });
    
    request.send();
    
    $('#alarmsSubmit').attr('disabled',true);
}