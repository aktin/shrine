package org.aktin.broker.i2b2.admin;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.aktin.broker.xml.RequestStatusInfo;

import de.sekmi.li2b2.api.crc.Query;
import de.sekmi.li2b2.api.crc.QueryExecution;
import de.sekmi.li2b2.api.crc.QueryResult;
import de.sekmi.li2b2.api.crc.QueryStatus;

public class BrokerI2b2Execution implements QueryExecution{

	private RequestStatusInfo status;
	private BrokerI2b2Query query;

	public BrokerI2b2Execution(BrokerI2b2Query query, RequestStatusInfo status) {
		this.query = query;
		this.status = status;
	}
	@Override
	public Query getQuery() {
		return query;
	}

	
	@Override
	public QueryStatus getStatus() {
		QueryStatus ret;
		if( status.getStatus() == null ){
			// no status means not yet retrieved
			return QueryStatus.WAITTOPROCESS;
		}
		switch( status.getStatus() ){
		case accepted:
		case processing:
			ret = QueryStatus.PROCESSING;
			break;
		case completed:
			ret = QueryStatus.COMPLETED;
			break;
		case failed:
		case rejected:
			ret = QueryStatus.ERROR;
			break;
		case retrieved:
		default:
			ret = QueryStatus.WAITTOPROCESS;
			break;
		}
		return ret;
	}

	@Override
	public String getLabel() {
		return "Node "+status.node;
	}

	@Override
	public List<? extends QueryResult> getResults() throws IOException {
		String data = query.broker.getResultString(query.getId(), status.node, "text/vnd.aktin.patient-count");
		if( data == null ){
			// no result
			return Collections.emptyList();
		}else{
			return Collections.singletonList(new PatientCountResult(Integer.parseInt(data), this));
		}
	}

}
