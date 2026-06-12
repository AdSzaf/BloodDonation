package Hospital;

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
 * Glowna petla:
 *  1. Losuj czas do kolejnego zapotrzebowania i zaczekaj (advanceTime).
 *  2. Wyslij interakcje Request (losuj: nagle / planowe, grupe krwi, ilosc).
 *  3. Przy odbiorze BloodTransported (w ambassadorze):
 *       - Wykonaj separacje na skladniki (losowy czas przetwarzania).
 *       - Oblicz wiek_krwi = czas_obecny - donationTime.
 *       - Zaktualizuj srednia liczbe dni od pobrania do wydania.
 *  4. Na koniec symulacji wydrukuj statystyki.
 */
public class HospitalFederate {

	public static final String READY_TO_RUN  = "ReadyToRun";
	private static final String FEDERATION_NAME = "BloodSupplyFederation";
	private static final String FOM_PATH = "foms/ProducerConsumer.xml";

	// ID szpitala - mozna ustawic przez argument lub stala
	private static final int HOSPITAL_ID = 1;

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

	// Logika szpitala
	Hospital hospital;

	// -----------------------------------------------------------------------

	private void log(String message) {
		System.out.println("HospitalFederate   : " + message);
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

		// Synchronizacja ReadyToRun
		rtiamb.registerFederationSynchronizationPoint(READY_TO_RUN, null);
		while (!fedamb.isAnnounced) rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		waitForUser();
		rtiamb.synchronizationPointAchieved(READY_TO_RUN);
		log("Osiagnieto punkt synchronizacji: " + READY_TO_RUN);
		while (!fedamb.isReadyToRun) rtiamb.evokeMultipleCallbacks(0.1, 0.2);

		enableTimePolicy();
		log("Polityka czasu wlaczona.");
		publishAndSubscribe();
		log("Publikowanie i subskrypcja skonfigurowane.");

		// Inicjalizacja logiki szpitala
		hospital = new Hospital(HOSPITAL_ID);

		// -----------------------------------------------------------------------
		// Glowna petla symulacji
		// -----------------------------------------------------------------------
		while (fedamb.isRunning) {

			// Krok 1: odczekaj losowy czas do kolejnego zapotrzebowania
			int timeToRequest = hospital.getTimeToNextRequest();
			advanceTime(timeToRequest);
			log("t=" + fedamb.federateTime + " | Generuje zapotrzebowanie...");

			// Krok 2: wyslij Request
			int     requestId       = hospital.nextRequestId();
			boolean isUrgent        = hospital.isUrgentRequest();
			String  bloodType       = hospital.randomBloodType();
			float   requestedAmount = hospital.randomRequestedAmount();

			sendRequest(requestId, HOSPITAL_ID, bloodType, requestedAmount, isUrgent);

			// Krok 3: jezeli minely 2 pelne kroki i nie dostalismy odpowiedzi
			// na to zamowienie - liczymy jako niedobor (uproszczenie)
			// Pelna implementacja wymagalaby sledzenia per-request timeout.
			// Tutaj niedobory sa rejestrowane przez ambassadora w onBloodTransported
			// gdy hospitalId sie nie zgadza lub przez brak callbacka po N krokach.
			// Dla uproszczenia: niedobory sa raportowane przez Transport (shortageCount)
			// a szpital liczy swoje "oczekujace zamowienia" w ambassadorze.

			log("t=" + fedamb.federateTime
					+ " | Zamowienie #" + requestId
					+ " | typ=" + bloodType
					+ " | ilosc=" + requestedAmount
					+ " | nagly=" + isUrgent
					+ " | Zlozone zamowienia=" + hospital.getTotalRequests()
					+ " | Dostarczone=" + hospital.getDeliveredUnits());
		}

		// Podsumowanie statystyk
		hospital.printStats();

		// Rezygnacja
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
				+ ", ilosc=" + requestedAmount
				+ ", nagly=" + isUrgent);
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
		// --- Publikuj Request ---
		requestHandle              = rtiamb.getInteractionClassHandle("HLAinteractionRoot.Request");
		reqRequestIdHandle         = rtiamb.getParameterHandle(requestHandle, "requestId");
		reqHospitalIdHandle        = rtiamb.getParameterHandle(requestHandle, "hospitalId");
		reqBloodTypeHandle         = rtiamb.getParameterHandle(requestHandle, "bloodType");
		reqRequestedAmountHandle   = rtiamb.getParameterHandle(requestHandle, "requestedAmount");
		reqIsUrgentHandle          = rtiamb.getParameterHandle(requestHandle, "isUrgent");
		rtiamb.publishInteractionClass(requestHandle);
		log("Publikuje: Request");

		// --- Subskrybuj BloodTransported ---
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

	// -----------------------------------------------------------------------
	public static void main(String[] args) {
		String federateName = (args.length > 0) ? args[0] : "Hospital";
		try {
			new HospitalFederate().runFederate(federateName);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}