package femr.ui.controllers;

import com.google.inject.Inject;
import com.google.inject.Provider;
import femr.business.dtos.CurrentUser;
import femr.business.dtos.ServiceResponse;
import femr.business.services.*;
import femr.common.models.*;
import femr.ui.models.pharmacy.CreateViewModelGet;
import femr.ui.models.pharmacy.CreateViewModelPost;
import femr.ui.views.html.pharmacies.index;
import femr.ui.views.html.pharmacies.populated;
import femr.util.calculations.dateUtils;
import femr.util.stringhelpers.StringUtils;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;

import java.util.ArrayList;
import java.util.List;

public class PharmaciesController extends Controller {
    private final Form<CreateViewModelPost> createViewModelPostForm = Form.form(CreateViewModelPost.class);
    private Provider<IPatientPrescription> patientPrescriptionProvider;
    private ISessionService sessionService;
    private ISearchService searchService;
    private ITriageService triageService;
    private IPharmacyService pharmacyService;
    private IMedicalService medicalService;

    @Inject
    public PharmaciesController(IPharmacyService pharmacyService,
                                ITriageService triageService,
                                ISessionService sessionService,
                                ISearchService searchService,
                                IMedicalService medicalService,
                                Provider<IPatientPrescription> patientPrescriptionProvider) {
        this.pharmacyService = pharmacyService;
        this.triageService = triageService;
        this.sessionService = sessionService;
        this.searchService = searchService;
        this.medicalService = medicalService;
        this.patientPrescriptionProvider = patientPrescriptionProvider;
    }

    public Result index() {
        boolean error = false;
        CurrentUser currentUserSession = sessionService.getCurrentUserSession();
        return ok(index.render(currentUserSession, error));
    }

    public Result createGet() {
        String s_id = request().getQueryString("id");

        //needs to validate an id was received from the query string
        s_id = s_id.trim();

        Integer id = Integer.parseInt(s_id);
        CreateViewModelGet viewModel = new CreateViewModelGet();
        CurrentUser currentUserSession = sessionService.getCurrentUserSession();
        Boolean error = false;

        //return to index if error finding a patient
        ServiceResponse<IPatient> patientServiceResponse = searchService.findPatientById(id);
        if (patientServiceResponse.hasErrors()) {
            error = true;
            return ok(index.render(currentUserSession, error));
        }

        IPatient patient = patientServiceResponse.getResponseObject();
        viewModel.setpID(patient.getId());
        viewModel.setFirstName(patient.getFirstName());
        viewModel.setLastName(patient.getLastName());
        viewModel.setAge(dateUtils.calculateYears(patient.getAge()));
        viewModel.setSex(patient.getSex());

        //return to index if error finding a patient encounter
        ServiceResponse<IPatientEncounter> patientEncounterServiceResponse = searchService.findCurrentEncounterByPatientId(id);
        if (patientEncounterServiceResponse.hasErrors()) {
            error = true;
            return ok(index.render(currentUserSession, error));
        }

        IPatientEncounter patientEncounter = patientEncounterServiceResponse.getResponseObject();
        viewModel.setWeeksPregnant(patientEncounter.getWeeksPregnant());

        //set viewModel field to null if patient vital does not exist
        ServiceResponse<IPatientEncounterVital> patientEncounterVitalServiceResponse;
        patientEncounterVitalServiceResponse = searchService.findPatientEncounterVitalByVitalIdAndEncounterId(5, patientEncounter.getId());
        if (patientEncounterVitalServiceResponse.hasErrors())
            viewModel.setHeightFeet(null);
        else
            viewModel.setHeightFeet(patientEncounterVitalServiceResponse.getResponseObject().getVitalValue());

        patientEncounterVitalServiceResponse = searchService.findPatientEncounterVitalByVitalIdAndEncounterId(6, patientEncounter.getId());
        if (patientEncounterVitalServiceResponse.hasErrors())
            viewModel.setHeightinches(null);
        else
            viewModel.setHeightinches(patientEncounterVitalServiceResponse.getResponseObject().getVitalValue());

        patientEncounterVitalServiceResponse = searchService.findPatientEncounterVitalByVitalIdAndEncounterId(7, patientEncounter.getId());
        if (patientEncounterVitalServiceResponse.hasErrors())
            viewModel.setWeight(null);
        else
            viewModel.setWeight(patientEncounterVitalServiceResponse.getResponseObject().getVitalValue());

        //find patient prescriptions
        List<? extends IPatientPrescription> patientPrescriptions = searchService.findPrescriptionsByEncounterId(patientEncounter.getId());
        List<String> dynamicViewMedications = new ArrayList<>();

        for (int filledPrescription = 0; filledPrescription < patientPrescriptions.size(); filledPrescription++) {
            if (patientPrescriptions.get(filledPrescription).getReplaced() != true) {
                dynamicViewMedications.add(patientPrescriptions.get(filledPrescription).getMedicationName());
            }
        }
        //this should probably be left as a List or ArrayList
        String[] viewMedications = new String[dynamicViewMedications.size()];
        viewMedications = dynamicViewMedications.toArray(viewMedications);
        viewModel.setMedications(viewMedications);

        //find patient problems
        List<? extends IPatientEncounterTreatmentField> patientEncounterProblems = searchService.findProblemsByEncounterId(patientEncounter.getId());
        int POSSIBLE_PROBLEMS = 5;
        String[] viewProblems = new String[POSSIBLE_PROBLEMS];

        if (patientEncounterProblems.size() > 0) {
            for (int problem = 0; problem < patientEncounterProblems.size(); problem++) {
                viewProblems[problem] = patientEncounterProblems.get(problem).getTreatmentFieldValue();
            }
        }
        viewModel.setProblems(viewProblems);

        return ok(populated.render(currentUserSession, viewModel, error));
    }

    public Result createPost(int id) {
        CreateViewModelPost createViewModelPost = createViewModelPostForm.bindFromRequest().get();
        ServiceResponse<IPatientEncounter> patientEncounterServiceResponse = searchService.findCurrentEncounterByPatientId(id);
        IPatientEncounter patientEncounter = patientEncounterServiceResponse.getResponseObject();
        CurrentUser currentUserSession = sessionService.getCurrentUserSession();

        //replace prescription 1
        if (StringUtils.isNotNullOrWhiteSpace(createViewModelPost.getReplacementMedication1())) {
            IPatientPrescription newPatientPrescription = initializeNewPrescription(currentUserSession, patientEncounter, createViewModelPost.getReplacementMedication1());
            ServiceResponse<IPatientPrescription> newPatientPrescriptionServiceResponse = medicalService.createPatientPrescription(newPatientPrescription);

            IPatientPrescription oldPatientPrescription = updateOldPrescription(newPatientPrescriptionServiceResponse.getResponseObject().getId(), patientEncounter.getId(), createViewModelPost.getPrescription1());
            ServiceResponse<IPatientPrescription> updatedOldPatientPrescription = pharmacyService.updatePatientPrescription(oldPatientPrescription);
        }
        //replace prescription 2
        if (StringUtils.isNotNullOrWhiteSpace(createViewModelPost.getReplacementMedication2())) {
            IPatientPrescription newPatientPrescription = initializeNewPrescription(currentUserSession, patientEncounter, createViewModelPost.getReplacementMedication2());
            ServiceResponse<IPatientPrescription> newPatientPrescriptionServiceResponse = medicalService.createPatientPrescription(newPatientPrescription);

            IPatientPrescription oldPatientPrescription = updateOldPrescription(newPatientPrescriptionServiceResponse.getResponseObject().getId(), patientEncounter.getId(), createViewModelPost.getPrescription2());
            ServiceResponse<IPatientPrescription> updatedOldPatientPrescription = pharmacyService.updatePatientPrescription(oldPatientPrescription);
        }
        //replace prescription 3
        if (StringUtils.isNotNullOrWhiteSpace(createViewModelPost.getReplacementMedication3())) {
            IPatientPrescription newPatientPrescription = initializeNewPrescription(currentUserSession, patientEncounter, createViewModelPost.getReplacementMedication3());
            ServiceResponse<IPatientPrescription> newPatientPrescriptionServiceResponse = medicalService.createPatientPrescription(newPatientPrescription);

            IPatientPrescription oldPatientPrescription = updateOldPrescription(newPatientPrescriptionServiceResponse.getResponseObject().getId(), patientEncounter.getId(), createViewModelPost.getPrescription3());
            ServiceResponse<IPatientPrescription> updatedOldPatientPrescription = pharmacyService.updatePatientPrescription(oldPatientPrescription);
        }
        //replace prescription 4
        if (StringUtils.isNotNullOrWhiteSpace(createViewModelPost.getReplacementMedication4())) {
            IPatientPrescription newPatientPrescription = initializeNewPrescription(currentUserSession, patientEncounter, createViewModelPost.getReplacementMedication4());
            ServiceResponse<IPatientPrescription> newPatientPrescriptionServiceResponse = medicalService.createPatientPrescription(newPatientPrescription);

            IPatientPrescription oldPatientPrescription = updateOldPrescription(newPatientPrescriptionServiceResponse.getResponseObject().getId(), patientEncounter.getId(), createViewModelPost.getPrescription4());
            ServiceResponse<IPatientPrescription> updatedOldPatientPrescription = pharmacyService.updatePatientPrescription(oldPatientPrescription);
        }
        //replace prescription 5
        if (StringUtils.isNotNullOrWhiteSpace(createViewModelPost.getReplacementMedication5())) {
            IPatientPrescription newPatientPrescription = initializeNewPrescription(currentUserSession, patientEncounter, createViewModelPost.getReplacementMedication5());
            ServiceResponse<IPatientPrescription> newPatientPrescriptionServiceResponse = medicalService.createPatientPrescription(newPatientPrescription);

            IPatientPrescription oldPatientPrescription = updateOldPrescription(newPatientPrescriptionServiceResponse.getResponseObject().getId(), patientEncounter.getId(), createViewModelPost.getPrescription5());
            ServiceResponse<IPatientPrescription> updatedOldPatientPrescription = pharmacyService.updatePatientPrescription(oldPatientPrescription);
        }
        return index();
    }

    private IPatientPrescription initializeNewPrescription(CurrentUser currentUserSession, IPatientEncounter patientEncounter, String medicationName) {
        IPatientPrescription patientPrescription = patientPrescriptionProvider.get();
        patientPrescription.setEncounterId(patientEncounter.getId());
        patientPrescription.setUserId(currentUserSession.getId());
        patientPrescription.setReplaced(false);
        patientPrescription.setReplacementId(null);
        patientPrescription.setMedicationName(medicationName);
        return patientPrescription;
    }

    private IPatientPrescription updateOldPrescription(int replacementId, int encounterId, String name) {
        ServiceResponse<IPatientPrescription> patientPrescriptionServiceResponse = pharmacyService.findPatientPrescriptionByEncounterIdAndPrescriptionName(encounterId, name);
        IPatientPrescription patientPrescription = patientPrescriptionServiceResponse.getResponseObject();
        patientPrescription.setReplaced(true);
        patientPrescription.setReplacementId(replacementId);
        return patientPrescription;
    }
}