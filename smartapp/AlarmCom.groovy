/**
 *  Alarm.com Service Manager
 *
 *  Author: Schwark Satyavolu
 *
 */
definition(
    name: "Alarm.com",
    namespace: "schwark",
    author: "Schwark Satyavolu",
    description: "Allows you to connect your Alarm.com alarm system with SmartThings and control them from your Things area or Dashboard in the SmartThings Mobile app.",
    category: "SmartThings Labs",
    iconUrl: "https://images-na.ssl-images-amazon.com/images/I/71yQ11GAAiL.png",
    iconX2Url: "https://images-na.ssl-images-amazon.com/images/I/71yQ11GAAiL.png",
    singleInstance: true
)

preferences {
	input("username", "string", title:"Username", description: "Please enter your Alarm.com username", required: true, displayDuringSetup: true)
	input("password", "password", title:"Password", description: "Please enter your Alarm.com password", required: true, displayDuringSetup: true)
}

/////////////////////////////////////
def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
}

def uninstalled() {
	log.debug("Uninstalling with settings: ${settings}")
	unschedule()

	removeChildDevices(getChildDevices())
}

/////////////////////////////////////
def updated() {
	//log.debug "Updated with settings: ${settings}"
	unsubscribe()
	initialize()
}

/////////////////////////////////////
def initialize() {
	// remove location subscription aftwards
	unsubscribe()
	state.subscribe = false

	runIn(60*5, doDeviceSync)
}

def getHubId() {
	return state.hubId ? state.hubId : location.hubs[0].id
}

/////////////////////////////////////
def locationHandler(evt) {
	log.debug "$locationHandler(evt.description)"
	def description = evt.description
	def hub = evt?.hubId
	state.hubId = hub
	log.debug("location handler: event description is ${description}")
}

/////////////////////////////////////
private def parseEventMessage(Map event) {
	//handles gateway attribute events
	return event
}

private def parseEventMessage(String description) {
}


private def getCommands() {
	def COMMANDS = [
					'ARMSTAY': ['params': ['ctl00$phBody$butArmStay':'Arm Stay', 'ctl00$phBody$cbArmOptionSilent': 'on'], 'name': 'Arm Stay'],
					'ARMAWAY': ['params': ['ctl00$phBody$butArmAway':'Arm Away', 'ctl00$phBody$cbArmOptionSilent': 'on'], 'name': 'Arm Away']
				   ]

	return COMMANDS
}

private def toQueryString(Map m)
{
	return m.collect { k, v -> "${k}=${URLEncoder.encode(v.toString())}" }.sort().join("&")
}

private def getRecipe(command) {
	def COMMANDS = getCommands()
	def STEPS = [
			['name': 'initlogin', 'uri': 'https://www.alarm.com/pda/', 'state': ['pda': /(?ms)pda\/([^\/]+)/]],
			['name': 'login', 'uri': 'https://www.alarm.com/pda/${pda}/default.aspx', 'method':'post', 'variables':[
			  	'__VIEWSTATE':'',
			  	'__VIEWSTATEGENERATOR':'',
			  	'__EVENTVALIDATION':'',
				'ctl00$ContentPlaceHolder1$txtLogin': settings.username,
			  	'ctl00$ContentPlaceHolder1$txtPassword': settings.password,
			  	'ctl00$ContentPlaceHolder1$btnLogin':'Login',
			 ], 'expect': /(?ms)Send a command to your system/, 'referer': 'self' ],
			 ['name': command, 'uri': 'https://www.alarm.com/pda/${pda}/main.aspx', 'method':'post', 'variables': ['__VIEWSTATE':'','__VIEWSTATEENCRYPTED':'','__EVENTVALIDATION':''] + COMMANDS[command]['params'], 'expect': /(?ms)The command should take effect/]
	]

	return STEPS.reverse()
}

private def runCommand(command, browserSession=[:]) {
	browserSession.vars = ['__VIEWSTATEGENERATOR':'','__EVENTVALIDATION':'','__VIEWSTATE':'']

	navigateUrl(getRecipe(command), browserSession)

	return browserSession
}

private def getPatternValue(html, browserSession, kind, variable, pattern=null) {
	if(!pattern) {
        pattern = /(?ms)name="${variable}".*?value="([^"]*)"/
    }
	//log.debug("looking for values with pattern ${pattern} for ${variable}")
	def value = null
	if(html) {
		if(!browserSession[kind]) browserSession[kind] = [:]
		def group = (html =~ pattern)
		if(group) {
			//log.debug "found variable value ${group[0][1]} for ${variable}"
			value = group[0][1]
			browserSession[kind][variable] = value
		}
	}
	return value
}

private def visitNodes(node, processor) {
	def stack = [node]
	
    def current = null
	while(stack && (current = stack.pop())) {
		if(processor) {
			if(processor(current)) return
		}
        if(current instanceof groovy.util.slurpersupport.Node) {
			current.children().each {
				stack.add(0,it)
			}
        }
	}
}

private def extractSession(params, response, browserSession) {
	//log.debug("extracting session variables..")
	def count = 1
	def html = response.data
    
    if(params.state) {
    	params.state.each { name, pattern ->
    		getPatternValue(html, browserSession, 'state', name, pattern)
    	}
	}

    browserSession.vars.each() { n, v ->
    	browserSession.vars[n] = ''
    }
        
    visitNodes(html[0]) {
    	if(it instanceof groovy.util.slurpersupport.Node && it.name == 'INPUT') {
        	def attr = it.attributes()
            if(attr) {
            	def name = attr['name']
                if(name) {
					def value = attr['value'] ? attr['value'] : ''
					if(browserSession.vars.containsKey(name)) {
						browserSession.vars[name] = value
						//log.debug "found form value ${value} for ${name}"
					}
				}
            }
        }
        return false
    }
    
	return browserSession
}

private def fillTemplate(template, map) {
	if(!map) return template
	def result = template.replaceAll(/\$\{(\w+)\}/) { k -> map[k[1]] ?: k[0] }
	return result
}

private def navigateUrl(recipe, browserSession) {
    def params = recipe.pop()

	def success = { response ->
    	log.trace("response status is ${response.status}")

    	browserSession.cookies = !browserSession.get('cookies') ? [] : browserSession.cookies
    	response.headerIterator('Set-Cookie').each {
    		log.debug "adding cookie to request: ${it}"
      		browserSession.cookies.add(it.value.split(';')[0])
    	}

    	if(response.status == 200) {
			extractSession(params, response, browserSession)
	    	if(params.processor) params.processor(response, browserSession)
	    	if(params.expect) {
	    		log.debug((response.data =~ params.expect) ? "${params.name} is successful" : "${params.name} has failed")
	    	}
	    } else if(response.status == 302) {
	    	response.headerIterator('Location').each {
    			def location = params.uri.toURI().resolve(it.value).toString()
    			log.debug "redirecting on 302 to: ${location}"
    			recipe.push(['name': "${params.name} redirect", 'uri': location, 'expect': params.expect, 'processor': params.processor])
    		}
	    }

		if(recipe) {
			if(!recipe[-1].referer) recipe[-1].referer = params.uri
			navigateUrl(recipe, browserSession)
		}

	    return browserSession
    }

	if(params.uri) {
		params.uri = fillTemplate(params.uri, browserSession.vars + browserSession.state)
        if(!params.headers) params.headers = [:]
		if(!params.headers['Origin']) params.headers['Host'] = params.uri.toURI().host
		params.headers['User-Agent'] = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/49.0.2623.110 Safari/537.36'
		params.headers['Accept'] = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
		params.headers['Accept-Language'] = 'en-US,en;q=0.5'

		if(params.referer == 'self') params.referer = params.uri
		if(params.referer) params.headers['Referer'] = params.referer
		if(browserSession.cookies) {
			params.headers['Cookie'] = browserSession.cookies.join(";")
		}
		if(browserSession.vars) {
			params.variables = (params.variables ? params.variables : [:])
			params.variables.each {name, value ->
				if(!value && browserSession.vars[name]) params.variables[name] = browserSession.vars[name]
			}
		}
		log.debug("navigating to ${params.uri} and method: ${params.method} and headers ${params.headers} and using params: ${params.variables}")
		try {
			if(params.method == 'post') {
				if(params.variables) params.body = toQueryString(params.variables)
				httpPost(params, success)
			} else {
				if(params.variables) params.query = params.variables
	    		httpGet(params,success)
   			}
		} catch (e) {
    			log.error "something went wrong: $e"
		}
	}

	return browserSession
}

/////////////////////////////////////
def doDeviceSync(){
	log.debug "Doing Alarm.com Device Sync!"

	if(!state.subscribe) {
		subscribe(location, null, locationHandler, [filterEvents:false])
		state.subscribe = true
	}

	createSwitches()
}


////////////////////////////////////////////
//CHILD DEVICE METHODS
/////////////////////////////////////
def parse(childDevice, description) {
	def parsedEvent = parseEventMessage(description)

	if (parsedEvent.headers && parsedEvent.body) {
		def headerString = new String(parsedEvent.headers.decodeBase64())
		def bodyString = new String(parsedEvent.body.decodeBase64())
		log.debug "parse() - ${bodyString}"
	} else {
		log.debug "parse - got something other than headers,body..."
		return []
	}
}

def createSwitches() {
	log.debug("Creating Alarm.com Switches...")
	if(state.childDevicesCreated) return

	def COMMANDS = getCommands()
	COMMANDS.each() { id, map ->
		def name = map['name']
		log.debug("processing switch ${id} with name ${name}")
		def PREFIX = "ALARMCOM"
		def hubId = getHubId()
		def alarmSwitch = addChildDevice("schwark", "Alarm.com Switch", "${PREFIX}${id}", hubId, ["name": "AlarmCom.${id}", "label": "${name}", "completedSetup": true])
		log.debug("created child device ${PREFIX}${id} with name ${name} and hub ${hubId}")
		alarmSwitch.setCommand(id)
	}
	state.childDevicesCreated = true
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Boolean canInstallLabs()
{
	return hasAllHubsOver("000.011.00603")
}

private Boolean hasAllHubsOver(String desiredFirmware)
{
	return realHubFirmwareVersions.every { fw -> fw >= desiredFirmware }
}

private List getRealHubFirmwareVersions()
{
	return location.hubs*.firmwareVersionString.findAll { it }
}

private removeChildDevices(data) {
    data.delete.each {
        deleteChildDevice(it.deviceNetworkId)
    }
}