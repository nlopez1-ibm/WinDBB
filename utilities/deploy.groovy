//From Naz Project: 
// github.ibm.com/dangeville-n/zAppBuild/blob/master/utilities/UCDDeployUtilities.groovy
// Patych for MFS DeplyType - need to zip the full PDS (member=*) 
// Not sure copytoHRS can handle this 
// See MyGroovy for pre-test

@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.build.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.report.*
import com.ibm.dbb.build.report.records.*
import groovy.time.*
import groovy.xml.MarkupBuilder
/**
 * This script creates a version in UrbanCode Deploy based on the build result.
 *
 * usage: deploy.groovy [options]
 *
 * options:
 *  -b,--buztool <file>           Absolute path to UrbanCode Deploy buztool.sh script
 *  -w,--workDir <dir>            Absolute path to the DBB build output directory
 *  -c,--component <name>         Name of the UCD component to create version in
 *  -h,--help                     Prints this message
 *  -sh,--deployhash              Hashtag associated with the commit
 *  -u,--url                      Url of the git repository
 *
 * note:
 *   This script uses ship list specification and buztool parameters which are
 *   introduced since UCD v6.2.6. When used with an earlier version of UCD, please
 *   modify the script to remove the code that creates the top level property and
 *   the code that uses the -o buztool parameter.
 */
// start create version
def properties = parseInput(args)
def startTime = new Date()
properties.startTime = startTime.format("yyyyMMdd.hhmmss.mmm")
println("** Create version start at $properties.startTime")
println("** Properties at startup:")
properties.each{k,v->
	println "   $k -> $v"
}
// get the src code webpage
def giturl = " "
if (properties.url.contains("@") ) {
	def temp1 = (properties.url.split('@'))
	def temp2 = (temp1[1].split(':'))
	def temp3 = (temp2[1].split(".git"))
	giturl = "https://" + temp2[0] + "/" + temp3[0] +  "/commit/" + properties.hasht
} else {
   giturl = properties.url + "/commit/" + properties.hasht
}
println("Github url :" + giturl)

// read build report data
println("** Read build report data from $properties.workDir/BuildReport.json")
def jsonOutputFile = new File("${properties.workDir}/BuildReport.json")
def buildReport= BuildReport.parse(new FileInputStream(jsonOutputFile))

// parse build report to find the build result meta info
def buildResult = buildReport.getRecords().findAll{it.getType()==DefaultRecordFactory.TYPE_BUILD_RESULT}[0];
def dependencies = buildReport.getRecords().findAll{it.getType()==DefaultRecordFactory.TYPE_DEPENDENCY_SET};

// parse build report to find the build outputs to be deployed.
println("** Find deployable outputs in the build report ")
// the following example finds all the build output with deployType set
def executes= buildReport.getRecords().findAll{
	it.getType()==DefaultRecordFactory.TYPE_EXECUTE &&
	!it.getOutputs().findAll{ o ->
		o.deployType != null
	}.isEmpty()
}

// the following example finds all the build output in *.LOAD data set
//def executes= buildReport.getRecords().findAll{
//	it.getType()==DefaultRecordFactory.TYPE_EXECUTE &&
//	!it.getOutputs().findAll{ o ->
//		def (ds,member) = getDatasetName(o.dataset)
//		return ds.endsWith(".LOAD")
//	}.isEmpty()
//}
executes.each { it.getOutputs().each { println("   ${it.dataset}, ${it.deployType}")}}

// generate ship list file. specification of UCD ship list can be found at
// https://www.ibm.com/support/knowledgecenter/SS4GSP_6.2.7/com.ibm.udeploy.doc/topics/zos_shiplistfiles.html
println("** Generate UCD ship list file")
def writer = new StringWriter()
writer.write("<?xml version=\"1.0\" encoding=\"CP037\"?>\n");
def xml = new MarkupBuilder(writer)
xml.manifest(type:"MANIFEST_SHIPLIST"){
	//top level property will be added as version properties
		//requires UCD v6.2.6 and above
//	property(name : buildResult.getGroup() + "-" + buildResult.getLabel(), value : buildResult.getUrl())
//	property(name : buildResult.getGroup() + "-" + "Git Version Info", value : giturl, Buildname : buildResult.getGroup() + "-" +
//             buildResult.getLabel(), Buildvalue : buildResult.getUrl() )
	//iterate through the outputs and add container and resource elements
	executes.each{ execute ->
		 execute.getOutputs().each{ output ->
			def (ds,member) = getDatasetName(output.dataset)
			container(name:ds, type:"PDS"){
				
				// small hack to be consistent between the scripted deploy and UCD deploy
				// ND's repo -ver - (NJL - shut down)
				//def fixedDeployType = output.deployType
				//if (fixedDeployType == "LOAD") {
				//	fixedDeployType = "CICS_LOAD"
				//}
				
				resource(name:member, type:"PDSMember", deployType:fixedDeployType){
					// add any custom properties needed
					property(name:"buildcommand", value:execute.getCommand())
					property(name:"buildoptions", value:execute.getOptions())
					// add source information
					inputs(url : giturl){
						input(name : execute.getFile(),  version : properties.hasht, compileType : "Main", url : buildResult.getUrl())
						dependencies.each{
							if(it.getId() == execute.getFile()){
								it.getAllDependencies().each{
									def displayName = it.getFile()? it.getFile() : it.getLname()
									input(name : displayName, compileType : it.getCategory())
								}
							}
						}
					}
				}
			}
		 }
	}
}
println("** Write ship list file to  $properties.workDir/shiplist.xml")
def shiplistFile = new File("${properties.workDir}/shiplist.xml")

shiplistFile.text = writer


// assemble and run UCD buztool command to create a version. An example of the command is like below
// /opt/ibm-ucd/agent/bin/buztool.sh createzosversion -c MYCOMP -s /var/dbb/workDir/shiplist.xml
// command parameters can be found at
// https://www.ibm.com/support/knowledgecenter/SS4GSP_6.2.7/com.ibm.udeploy.doc/topics/zos_runtools_uss.html

println("** Create version by running UCD buztool")
def cmd = [ properties.buztoolPath,
		"createzosversion",
	"-c",
	properties.component,
	   "-s",
	"$properties.workDir/shiplist.xml",
		//requires UCD v6.2.6 and above
	"-o",
	"${properties.workDir}/buztool.output"
		]
def cmdStr = "";
cmd.each{ cmdStr = cmdStr + it + " "}
println cmdStr
def p = cmd.execute();
def output = new StringWriter(), error = new StringWriter()
p.waitForProcessOutput(output, error)
println "OUT: $output"
println "ERR: $error"
//def p = new ProcessBuilder(cmd).redirectErrorStream(true);
//p.waitFor();
//println(p.text);

def rc = p.exitValue();
if(rc==0){
	println("** buztool output properties")
	def outputProp = new Properties()
	new File("${properties.workDir}/buztool.output").withInputStream {
		outputProp.load(it)
	}
	outputProp.each{k,v->
		println "   $k -> $v"
	}
	
	
	def f=shiplistFile.getText('CP037')
	new File("${properties.workDir}/shiplistReport.xml").write(f,'UTF-8')
	
	
/*
	println "iconv shiplist to UTF-8"
		def cmdch = "iconv -f IBM-1047 -t UTF-8  ${properties.workDir}/shiplist.xml"
		cmdch.execute().with{
			def outputch = new StringWriter()

			def errorch = new StringWriter()
			//wait for process ended and catch stderr and stdout.
			it.waitForProcessOutput(outputch, errorch)
			//check there is no error
			println "error=$errorch"
			println "output=$outputch"
			println "code=${it.exitValue()}"
			def file2 = new File("${properties.workDir}/shiplistReport.xml")
			file2.text = outputch
		}
		*/
}else{
	System.exit(rc)
}

/**
 * parse data set name and member name
 * @param fullname e.g. BLD.LOAD(PGM1)
 * @return e.g. (BLD.LOAD, PGM1)
 */
def getDatasetName(String fullname){
	def ds,member;
	def elements =  fullname.split("[\\(\\)]");
	ds = elements[0];
	member = elements.size()>1? elements[1] : "";
	return [ds,member];
}

def parseInput(String[] cliArgs){
	   println("parse output")
	def cli = new CliBuilder(usage: "deploy.groovy [options]")
	cli.b(longOpt:'buztool', args:1, argName:'file', 'Absolute path to UrbanCode Deploy buztool.sh script')
	cli.w(longOpt:'workDir', args:1, argName:'dir', 'Absolute path to the DBB build output directory')
	cli.c(longOpt:'component', args:1, argName:'name', 'Name of the UCD component to create version in')
	cli.h(longOpt:'help', 'Prints this message')
	cli.sh(longOpt:'deployhash', args:1, argName:'hash','The commit hash')
	cli.u(longOpt:'url', args:1, argName:'url','The git repo url')
	def opts = cli.parse(cliArgs)
	if (opts.h) { // if help option used, print usage and exit
		 cli.usage()
		System.exit(0)
	}

	def properties = new Properties()

	// load workDir from ./build.properties if it exists
	def buildProperties = new Properties()
	def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
	def buildPropFile = new File("$scriptDir/build.properties")
	if (buildPropFile.exists()){
		buildPropFile.withInputStream {
				buildProperties.load(it)
		}
		properties.workDir = buildProperties.workDir
	}

	// set command line arguments
	if (opts.w) properties.workDir = opts.w
	if (opts.b) properties.buztoolPath = opts.b
	if (opts.c) properties.component = opts.c
	if (opts.sh) properties.hasht = opts.sh
	if (opts.u) properties.url = opts.u

	// validate required properties
	try {
		assert properties.buztoolPath : "Missing property buztool script path"
		assert properties.workDir: "Missing property build output directory"
		assert properties.component: "Missing property UCD component"
	} catch (AssertionError e) {
		cli.usage()
		throw e
	}
	return properties
}
