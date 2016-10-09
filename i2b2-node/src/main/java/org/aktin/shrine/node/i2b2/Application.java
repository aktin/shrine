package org.aktin.shrine.node.i2b2;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.aktin.broker.client.BrokerClient;
import org.aktin.broker.xml.RequestInfo;
import org.aktin.broker.xml.RequestStatus;
import org.aktin.broker.xml.SoftwareModule;
import org.w3c.dom.Document;

import de.sekmi.li2b2.client.Li2b2Client;
import de.sekmi.li2b2.client.crc.MasterInstanceResult;
import de.sekmi.li2b2.client.crc.QueryResultInstance;
import de.sekmi.li2b2.hive.ErrorResponseException;
import de.sekmi.li2b2.hive.HiveException;
import de.sekmi.li2b2.hive.crc.QueryResultType;

public class Application {
	long startup;
	Li2b2Client i2b2;
	BrokerClient broker;
	DocumentBuilder builder;

	private static final String MEDIA_TYPE_I2B2_QUERY_DEFINITION = "text/vnd.i2b2.query-definition+xml";
	private static final String MEDIA_TYPE_I2B2_RESULT_OUTPUT_LIST = "text/vnd.i2b2.result-output-list+xml";
//	private static final String MEDIA_TYPE_I2B2_RESULT_ENVELOPE = "text/vnd.i2b2.result-envelope+xml";
	
	public Application() throws ParserConfigurationException{
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		builder = factory.newDocumentBuilder();
		this.startup = System.currentTimeMillis();
	}
	public void connectI2b2(String pm_service, String user, String domain, String password) throws IOException, ErrorResponseException, HiveException{
		Li2b2Client client = new Li2b2Client();
		client.setAuthorisation(user, password, domain);
		client.setPM(new URL(pm_service));
		client.PM().requestUserConfiguration();
		this.i2b2 = client;
		
	}
	public void connectBroker(String broker_endpoint) throws IOException{
		try {
			this.broker = new BrokerClient(new URI(broker_endpoint));
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}
		// optional status exchange
		broker.getBrokerStatus();
		broker.postMyStatus(startup, new SoftwareModule("org.aktin.broker.i2b2.node", "1.0-SNAPSHOT"));
	}
	public void issueWarning(String warning){
		
	}
	private void postOnlyPatientCount(RequestInfo request, MasterInstanceResult mir) throws IOException{
		Integer count = null;
		for( QueryResultInstance qr : mir.query_result_instance ){
			if( qr.query_result_type.display_type.equals(QueryResultType.I2B2_DISPLAY_CATNUM) 
					&& qr.set_size != null){
				//  use set_size
				count = qr.set_size;
				break;
			}
			// submit results to aggregator
			broker.putRequestResult(request.getId(), "text/vnd.aktin.patient-count", Objects.toString(count));
		}		
	}
	// TODO for second version, concatenate the results (each starting with <?xml)
	// TODO for third version, add multiple result documents
	private void postConcatenatedResults(RequestInfo request, MasterInstanceResult mir) throws IOException{
		StringBuilder sb = new StringBuilder();
		for( QueryResultInstance qr : mir.query_result_instance ){
			switch( qr.query_result_type.display_type ){
			case QueryResultType.I2B2_DISPLAY_CATNUM:
				String doc;
				try {
					doc = i2b2.CRC().getResultDocument(qr.result_instance_id);
				} catch (HiveException e) {
					issueWarning("Unable to retrieve result document: "+e.getMessage());
					continue;
				}
				sb.append(doc);
				sb.append('\n');
				break;
			default:
				issueWarning("Ignoring unsupported result type '"+qr.query_result_type.display_type+"': "+qr.query_result_type.description);
			}

		}
		// submit results to aggregator
		broker.putRequestResult(request.getId(), "application/vnd.i2b2.concat-results+xml", sb.toString());
	}
	public void processRequests() throws IOException{
		List<RequestInfo> requests = broker.listMyRequests();
		// process requests synchronously. first come first serve
		for( RequestInfo request : requests ){
			// check media type
			if( !request.hasMediaType(MEDIA_TYPE_I2B2_QUERY_DEFINITION) ){
				// need query definition
				System.err.println("Unable to process query "+request.getId()+" without "+MEDIA_TYPE_I2B2_QUERY_DEFINITION);
				continue;
			}else if( !request.hasMediaType(MEDIA_TYPE_I2B2_RESULT_OUTPUT_LIST) ){
				// need output list
				System.err.println("Unable to process query "+request.getId()+" without "+MEDIA_TYPE_I2B2_RESULT_OUTPUT_LIST);
				continue;
			}
			// retrieve definition
			Document def = broker.getMyRequestDefinitionXml(request.getId(), MEDIA_TYPE_I2B2_QUERY_DEFINITION);
			// retrieve result output list
			String[] resultList = broker.getMyRequestDefinitionLines(request.getId(), MEDIA_TYPE_I2B2_RESULT_OUTPUT_LIST);
			// run query definition
			MasterInstanceResult mir;
			try {
				mir = i2b2.CRC().runQueryInstance(def.getDocumentElement(), resultList);
			} catch (HiveException e) {
				// TODO log error
				// TODO report error message
				broker.postRequestStatus(request.getId(), RequestStatus.failed);
				continue;
			}
			// retrieve results for primary instance as listed
			// this first version only reports the patient count as extracted from set_size
			postOnlyPatientCount(request, mir);
			// 
			// report completed
			broker.postRequestStatus(request.getId(), RequestStatus.completed);
			
			System.out.println("Completed request #"+request.getId());
			// delete request
			broker.deleteMyRequest(request.getId());
		}
		
	}

	/**
	 * 
	 * To run this via the harvard demo i2b2 server, use the following 
	 * command line argumenst: {@code java org.aktin.broker.i2b2.node.Application http://services.i2b2.org/i2b2/services/PMService/ demo@i2b2demo demouser 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception{
		// TODO Auto-generated method stub
		String i2b2_pm_service = args[0];
		String i2b2_user = args[1];
		String i2b2_pass = args[2];
		String broker_service = args[3];

		// setup i2b2 client
		String i2b2_domain = null;
		// use domain name, if provided
		int at = i2b2_user.indexOf('@');
		if( at != -1 ){
			i2b2_domain = i2b2_user.substring(at+1);
			i2b2_user = i2b2_user.substring(0, at);
		}
		// TODO broker keystore
		// setup broker client
		Application app = new Application();
		app.connectI2b2(i2b2_pm_service, i2b2_user, i2b2_domain, i2b2_pass);
		app.connectBroker(broker_service);
		app.processRequests();
	}

}
