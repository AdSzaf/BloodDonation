package Transport;

import hla.rti1516e.*;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.HLAASCIIstring;
import hla.rti1516e.encoding.HLAfloat32BE;
import hla.rti1516e.encoding.HLAfloat64BE;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.time.HLAfloat64Time;
import org.portico.impl.hla1516e.types.encoding.HLA1516eASCIIstring;
import org.portico.impl.hla1516e.types.encoding.HLA1516eFloat32BE;
import org.portico.impl.hla1516e.types.encoding.HLA1516eFloat64BE;
import org.portico.impl.hla1516e.types.encoding.HLA1516eInteger32BE;

/**
 * Ambassador federatu Transport.
 * Odbiera BloodCollected -> przekazuje do Transport.addBlood()
 * Odbiera Request        -> przekazuje do TransportFederate.handleRequest()
 */
public class TransportFederateAmbassador extends NullFederateAmbassador {

	private final TransportFederate federate;

	protected double  federateTime      = 0.0;
	protected double  federateLookahead = 1.0;

	protected boolean isRegulating  = false;
	protected boolean isConstrained = false;
	protected boolean isAdvancing   = false;
	protected boolean isAnnounced   = false;
	protected boolean isReadyToRun  = false;
	protected boolean isRunning     = true;

	public TransportFederateAmbassador(TransportFederate federate) {
		this.federate = federate;
	}

	private void log(String msg) {
		System.out.println("[" + federate.getFederateName() + " Amb] : " + msg);

	}

	// -----------------------------------------------------------------------
	// Synchronizacja
	// -----------------------------------------------------------------------

	@Override
	public void synchronizationPointRegistrationFailed(String label,
													   SynchronizationPointFailureReason reason) {
		log("Blad rejestracji punktu sync: " + label + ", powod=" + reason);
	}

	@Override
	public void synchronizationPointRegistrationSucceeded(String label) {
		log("Zarejestrowano punkt sync: " + label);
	}

	@Override
	public void announceSynchronizationPoint(String label, byte[] tag) {
		log("Ogloszono punkt sync: " + label);
		if (label.equals(TransportFederate.READY_TO_RUN))
			this.isAnnounced = true;
	}

	@Override
	public void federationSynchronized(String label, FederateHandleSet failed) {
		log("Federacja zsynchronizowana: " + label);
		if (label.equals(TransportFederate.READY_TO_RUN))
			this.isReadyToRun = true;
	}

	// -----------------------------------------------------------------------
	// Czas
	// -----------------------------------------------------------------------

	@Override
	public void timeRegulationEnabled(LogicalTime time) {
		this.federateTime = ((HLAfloat64Time) time).getValue();
		this.isRegulating = true;
	}

	@Override
	public void timeConstrainedEnabled(LogicalTime time) {
		this.federateTime = ((HLAfloat64Time) time).getValue();
		this.isConstrained = true;
	}

	@Override
	public void timeAdvanceGrant(LogicalTime time) {
		this.federateTime = ((HLAfloat64Time) time).getValue();
		this.isAdvancing = false;
	}

	// -----------------------------------------------------------------------
	// Odbior interakcji
	// -----------------------------------------------------------------------

	@Override
	public void receiveInteraction(InteractionClassHandle interactionClass,
								   ParameterHandleValueMap theParameters,
								   byte[] tag,
								   OrderType sentOrdering,
								   TransportationTypeHandle theTransport,
								   SupplementalReceiveInfo receiveInfo)
			throws FederateInternalError {
		receiveInteraction(interactionClass, theParameters, tag,
				sentOrdering, theTransport, null, sentOrdering, receiveInfo);
	}

	@Override
	public void receiveInteraction(InteractionClassHandle interactionClass,
								   ParameterHandleValueMap theParameters,
								   byte[] tag,
								   OrderType sentOrdering,
								   TransportationTypeHandle theTransport,
								   LogicalTime time,
								   OrderType receivedOrdering,
								   SupplementalReceiveInfo receiveInfo)
			throws FederateInternalError {

		// ---- BloodCollected ------------------------------------------------
		if (interactionClass.equals(federate.bloodCollectedHandle)) {
			try {
				HLAinteger32BE decId = new HLA1516eInteger32BE();
				decId.decode(theParameters.get(federate.bcBloodIdHandle));
				int bloodId = decId.getValue();

				HLAfloat32BE decAmount = new HLA1516eFloat32BE();
				decAmount.decode(theParameters.get(federate.bcBloodAmountHandle));
				float bloodAmount = decAmount.getValue();

				HLAASCIIstring decType = new HLA1516eASCIIstring();
				decType.decode(theParameters.get(federate.bcBloodTypeHandle));
				String bloodType = decType.getValue();

				HLAfloat64BE decTime = new HLA1516eFloat64BE();
				decTime.decode(theParameters.get(federate.bcDonationTimeHandle));
				double donationTime = decTime.getValue();

				HLAinteger32BE decMobile = new HLA1516eInteger32BE();
				decMobile.decode(theParameters.get(federate.bcIsMobileHandle));
				boolean isMobile = decMobile.getValue() != 0;

				log("Odebrano BloodCollected: id=" + bloodId
						+ ", typ=" + bloodType
						+ ", ilosc=" + bloodAmount
						+ ", donationTime=" + donationTime
						+ ", mobilny=" + isMobile);

				Transport.getInstance().addBlood(bloodId, bloodAmount, bloodType, donationTime);

			} catch (DecoderException e) {
				log("Blad dekodowania BloodCollected: " + e.getMessage());
			}
		}

		// ---- Request -------------------------------------------------------
		else if (interactionClass.equals(federate.requestHandle)) {
			try {
				HLAinteger32BE decReqId = new HLA1516eInteger32BE();
				decReqId.decode(theParameters.get(federate.reqRequestIdHandle));
				int requestId = decReqId.getValue();

				HLAinteger32BE decHospId = new HLA1516eInteger32BE();
				decHospId.decode(theParameters.get(federate.reqHospitalIdHandle));
				int hospitalId = decHospId.getValue();

				HLAASCIIstring decType = new HLA1516eASCIIstring();
				decType.decode(theParameters.get(federate.reqBloodTypeHandle));
				String bloodType = decType.getValue();

				HLAfloat32BE decAmt = new HLA1516eFloat32BE();
				decAmt.decode(theParameters.get(federate.reqRequestedAmountHandle));
				float requestedAmount = decAmt.getValue();

				HLAinteger32BE decUrgent = new HLA1516eInteger32BE();
				decUrgent.decode(theParameters.get(federate.reqIsUrgentHandle));
				boolean isUrgent = decUrgent.getValue() != 0;

				log("Odebrano Request #" + requestId
						+ " od szpitala=" + hospitalId
						+ ", typ=" + bloodType
						+ ", ilosc=" + requestedAmount
						+ ", nagly=" + isUrgent);

				federate.handleRequest(requestId, hospitalId, bloodType,
						requestedAmount, isUrgent);

			} catch (DecoderException e) {
				log("Blad dekodowania Request: " + e.getMessage());
			}
		}
	}

	// -----------------------------------------------------------------------
	// Obiekty (Transport nie subskrybuje obiektow - minimalna implementacja)
	// -----------------------------------------------------------------------

	@Override
	public void discoverObjectInstance(ObjectInstanceHandle theObject,
									   ObjectClassHandle theObjectClass,
									   String objectName) throws FederateInternalError {
		log("Odkryto obiekt: " + objectName);
	}

	@Override
	public void removeObjectInstance(ObjectInstanceHandle theObject,
									 byte[] tag,
									 OrderType sentOrdering,
									 SupplementalRemoveInfo removeInfo) throws FederateInternalError {
		log("Usunieto obiekt: " + theObject);
	}
}