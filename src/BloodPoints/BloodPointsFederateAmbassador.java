package BloodPoints;

import hla.rti1516e.*;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.time.HLAfloat64Time;

/**
 * Ambassador federatu BloodPoints.
 * BloodPoints tylko publikuje BloodCollected - nie subskrybuje zadnych interakcji
 * ani obiektow, wiec callbacks sa tu minimalne.
 */
public class BloodPointsFederateAmbassador extends NullFederateAmbassador {

	private final BloodPointsFederate federate;

	protected double federateTime      = 0.0;
	protected double federateLookahead = 1.0;

	protected boolean isRegulating  = false;
	protected boolean isConstrained = false;
	protected boolean isAdvancing   = false;
	protected boolean isAnnounced   = false;
	protected boolean isReadyToRun  = false;
	protected boolean isRunning     = true;

	public BloodPointsFederateAmbassador(BloodPointsFederate federate) {
		this.federate = federate;
	}

	private void log(String msg) {
		System.out.println("[" + federate.getFederateName() + " Amb] : " + msg);
	}

	@Override
	public void synchronizationPointRegistrationFailed(String label,
													   SynchronizationPointFailureReason reason) {
		log("Nie udalo sie zarejestrowac punktu synchronizacji: " + label + ", powod=" + reason);
	}

	@Override
	public void synchronizationPointRegistrationSucceeded(String label) {
		log("Zarejestrowano punkt synchronizacji: " + label);
	}

	@Override
	public void announceSynchronizationPoint(String label, byte[] tag) {
		log("Ogłoszono punkt synchronizacji: " + label);
		if (label.equals(BloodPointsFederate.READY_TO_RUN))
			this.isAnnounced = true;
	}

	@Override
	public void federationSynchronized(String label, FederateHandleSet failed) {
		log("Federacja zsynchronizowana: " + label);
		if (label.equals(BloodPointsFederate.READY_TO_RUN))
			this.isReadyToRun = true;
	}

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
}