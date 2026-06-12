package Hospital;

import core.Main;
import hla.rti1516e.*;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.exceptions.*;
import hla.rti1516e.time.HLAfloat64Interval;
import hla.rti1516e.time.HLAfloat64Time;
import hla.rti1516e.time.HLAfloat64TimeFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Federat Hospital - Szpital / Bank Krwi Szpitalny.
 *
 * Publikuje:   Request
 * Subskrybuje: BloodTransported
 *
 * Zmiany v3:
 *  - uzywa Hospital.registerRequest() zamiast nextRequestId()
 *  - wywoluje Hospital.checkTimeouts() co krok - liczy wlasny wskaznik niedoboru
 *  - petla co 1 j.s. (advanceTime(1)) + co MIN_REQUEST_INTERVAL generuje zamowienie
 */
public class HospitalFederate {

	public static final String READY_TO_RUN   = "ReadyToRun";
	private static final String FEDERATION_NAME = "BloodSupplyFederation";
	private static final String FOM_PATH        = "foms/ProducerConsumer.xml";
	private int hospitalId;
	private String federateName;


	public HospitalFederate(int hospitalId) {
		this.hospitalId = hospitalId;
	}
	// RTI
	private RTIambassador rtiamb;
	private HospitalFederateAmbassador fedamb;
	private HLAfloat64TimeFactory timeFactory;
	protected EncoderFactory encoderFactory;

	// --- Uchwyty Request (publikacja) ---
	protected InteractionClassHandle requestHandle;
	protected ParameterHandle reqRequestIdHandle;
	protected ParameterHandle reqHospitalIdHandle;
	protected ParameterHandle reqBloodTypeHandle;
	protected ParameterHandle reqRequestedAmountHandle;
	protected ParameterHandle reqIsUrgentHandle;

	// --- Uchwyty BloodTransported (subskrypcja) ---
	protected InteractionClassHandle bloodTransportedHandle;
	protected ParameterHandle btBloodIdHandle;
	protected ParameterHandle btHospitalIdHandle;
	protected ParameterHandle btBloodAmountHandle;
	protected ParameterHandle btBloodTypeHandle;
	protected ParameterHandle btDonationTimeHandle;

	Hospital hospital;

	// Czas do nastepnego zamowienia - odliczany w petli krokowej
	private double timeToNextRequest = 0;

	// -----------------------------------------------------------------------

	private void log(String message) {
		System.out.println("[" + federateName + "] : " + message);
	}

	private void waitForUser() {
		log(">>>>>>>>>> Nacisnij Enter aby kontynuowac <<<<<<<<<<");
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		try { reader.readLine(); }
		catch (Exception e) { log("Blad: " + e.getMessage()); }
	}

	// -----------------------------------------------------------------------
	// Glowna metoda symulacji
	// -----------------------------------------------------------------------
	public void runFederate(String federateName) throws Exception {
		this.federateName = federateName;

		log("Tworze RTIambassador...");
		rtiamb = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
		encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();
		fedamb = new HospitalFederateAmbassador(this);
		rtiamb.connect(fedamb, CallbackModel.HLA_EVOKED);

		log("Tworze/dolaczam do federacji...");
		try {
			URL[] modules = { (new File(FOM_PATH)).toURI().toURL() };
			rtiamb.createFederationExecution(FEDERATION_NAME, modules);
			log("Federacja utworzona.");
		} catch (FederationExecutionAlreadyExists e) {
			log("Federacja juz istnieje - dolaczam.");
		} catch (MalformedURLException e) {
			log("Blad URL FOM: " + e.getMessage()); return;
		}

		rtiamb.joinFederationExecution(federateName, "hospital", FEDERATION_NAME);
		log("Dolaczono jako " + federateName);
		this.timeFactory = (HLAfloat64TimeFactory) rtiamb.getTimeFactory();

		//rtiamb.registerFederationSynchronizationPoint(READY_TO_RUN, null);
		while (!fedamb.isAnnounced) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		while (!Main.startSimulation) {
			Thread.sleep(100);
		}
		rtiamb.synchronizationPointAchieved(READY_TO_RUN);
		log("Osiagnieto punkt synchronizacji: " + READY_TO_RUN);
		while (!fedamb.isReadyToRun) rtiamb.evokeMultipleCallbacks(0.1, 0.2);

		enableTimePolicy();
		log("Polityka czasu wlaczona.");
		publishAndSubscribe();
		log("Publikowanie i subskrypcja skonfigurowane.");

		hospital = new Hospital(this.hospitalId);
		// Pierwsze zamowienie po losowym czasie
		timeToNextRequest = hospital.getTimeToNextRequest();

		// -----------------------------------------------------------------------
		// Glowna petla - krok co 1 j.s. (jak Transport)
		// Dzieki temu checkTimeouts() dziala dokladnie, a zamowienia
		// sa generowane gdy odlicznik dobiegnie do 0
		// -----------------------------------------------------------------------
		while (fedamb.isRunning) {
			advanceTime(1.0);
			double currentTime = fedamb.federateTime;

			// Sprawdz timeouty otwartych zamowien -> liczy wlasny wskaznik niedoboru
			hospital.checkTimeouts(currentTime);

			// Odlicz czas do kolejnego zamowienia
			timeToNextRequest -= 1.0;
			if (timeToNextRequest <= 0) {
				boolean isUrgent        = hospital.isUrgentRequest();
				String  bloodType       = hospital.randomBloodType();
				float   requestedAmount = hospital.randomRequestedAmount();

				// Zarejestruj zamowienie lokalnie (zapisuje czas, zeby moc liczyc timeout)
				int requestId = hospital.registerRequest(isUrgent, currentTime);

				sendRequest(requestId, hospitalId, bloodType, requestedAmount, isUrgent);

				log("t=" + currentTime
						+ " | Zamowienie #" + requestId
						+ " typ=" + bloodType
						+ " ilosc=" + requestedAmount
						+ (isUrgent ? " [NAGLE]" : " [planowe]")
						+ " | Zlozone=" + hospital.getTotalRequests()
						+ " Oczekujace=" + hospital.getPendingCount()
						+ " Dostarczone=" + hospital.getDeliveredUnits()
						+ " | Wskaznik niedoboru: "
						+ String.format("%.1f", hospital.getShortageRate()) + "%");

				// Zaplanuj kolejne zamowienie
				timeToNextRequest = hospital.getTimeToNextRequest();
			}
		}

		hospital.printStats();

		rtiamb.resignFederationExecution(ResignAction.DELETE_OBJECTS);
		log("Zrezygnowano z federacji.");
		try {
			rtiamb.destroyFederationExecution(FEDERATION_NAME);
		} catch (FederationExecutionDoesNotExist | FederatesCurrentlyJoined e) {
			log("Nie mozna zniszczyc federacji: " + e.getMessage());
		}
	}

	// -----------------------------------------------------------------------
	// Wyslanie interakcji Request
	// -----------------------------------------------------------------------
	private void sendRequest(int requestId, int hospitalId, String bloodType,
							 float requestedAmount, boolean isUrgent) throws RTIexception {

		ParameterHandleValueMap params = rtiamb.getParameterHandleValueMapFactory().create(5);

		params.put(reqRequestIdHandle,
				encoderFactory.createHLAinteger32BE(requestId).toByteArray());
		params.put(reqHospitalIdHandle,
				encoderFactory.createHLAinteger32BE(hospitalId).toByteArray());
		params.put(reqBloodTypeHandle,
				encoderFactory.createHLAASCIIstring(bloodType).toByteArray());
		params.put(reqRequestedAmountHandle,
				encoderFactory.createHLAfloat32BE(requestedAmount).toByteArray());
		params.put(reqIsUrgentHandle,
				// HLAboolean w Portico = HLAinteger32BE (0=false, 1=true)
				encoderFactory.createHLAinteger32BE(isUrgent ? 1 : 0).toByteArray());

		rtiamb.sendInteraction(requestHandle, params, generateTag());
		log("Wyslano Request #" + requestId
				+ ": typ=" + bloodType
				+ " ilosc=" + requestedAmount
				+ (isUrgent ? " [NAGLE]" : " [planowe]"));
	}

	// -----------------------------------------------------------------------
	// Metody pomocnicze HLA
	// -----------------------------------------------------------------------
	private void enableTimePolicy() throws Exception {
		HLAfloat64Interval lookahead = timeFactory.makeInterval(fedamb.federateLookahead);
		rtiamb.enableTimeRegulation(lookahead);
		while (!fedamb.isRegulating) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		rtiamb.enableTimeConstrained();
		while (!fedamb.isConstrained) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
	}

	private void publishAndSubscribe() throws RTIexception {
		requestHandle              = rtiamb.getInteractionClassHandle("HLAinteractionRoot.Request");
		reqRequestIdHandle         = rtiamb.getParameterHandle(requestHandle, "requestId");
		reqHospitalIdHandle        = rtiamb.getParameterHandle(requestHandle, "hospitalId");
		reqBloodTypeHandle         = rtiamb.getParameterHandle(requestHandle, "bloodType");
		reqRequestedAmountHandle   = rtiamb.getParameterHandle(requestHandle, "requestedAmount");
		reqIsUrgentHandle          = rtiamb.getParameterHandle(requestHandle, "isUrgent");
		rtiamb.publishInteractionClass(requestHandle);
		log("Publikuje: Request");

		bloodTransportedHandle     = rtiamb.getInteractionClassHandle("HLAinteractionRoot.BloodTransported");
		btBloodIdHandle            = rtiamb.getParameterHandle(bloodTransportedHandle, "bloodId");
		btHospitalIdHandle         = rtiamb.getParameterHandle(bloodTransportedHandle, "hospitalId");
		btBloodAmountHandle        = rtiamb.getParameterHandle(bloodTransportedHandle, "bloodAmount");
		btBloodTypeHandle          = rtiamb.getParameterHandle(bloodTransportedHandle, "bloodType");
		btDonationTimeHandle       = rtiamb.getParameterHandle(bloodTransportedHandle, "donationTime");
		rtiamb.subscribeInteractionClass(bloodTransportedHandle);
		log("Subskrybuje: BloodTransported");
	}

	private void advanceTime(double timestep) throws RTIexception {
		fedamb.isAdvancing = true;
		HLAfloat64Time time = timeFactory.makeTime(fedamb.federateTime + timestep);
		rtiamb.timeAdvanceRequest(time);
		while (fedamb.isAdvancing) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
	}

	private byte[] generateTag() {
		return ("(ts)" + System.currentTimeMillis()).getBytes();
	}

	public String getFederateName() {
		return federateName;
	}

	// -----------------------------------------------------------------------
	public static void main(String[] args) {
		String federateName = (args.length > 0) ? args[0] : "Hospital";
		try {
			new HospitalFederate(1).runFederate(federateName);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}