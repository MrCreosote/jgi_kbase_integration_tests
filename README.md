JGI / KBase Push to KBase integration tests
===========================================

This repo contains JUnit tests that exercise the the
[Push to KBase](https://kbase.us/transfer-jgi-data/) (PtKB)
functionality jointly developed by [KBase](http://kbase.us) and
[JGI](http://jgi.doe.gov/).

Warning and prerequisites
-------------------------

The tests contained here are time consuming and difficult to set up due to the
number and complexity of the systems involved. There are probably only a few
people that will need to set up and run the tests, and more or less these
people are presumed to be experts in the KBase and JGI APIs and systems.

### Prerequisites

Anyone attempting to run the tests should have a reasonable working knowledge
of (versions are given as of the time of writing):

* [KBase runtime](https://github.com/kbase/bootstrap)
* [KBase Workspace Service](https://github.com/kbase/workspace_deluxe) (WSS) 0.3.4
* [KBase Shock Service](https://github.com/kbase/shock_service) 0.9.6
* [KBase Handle Service](https://github.com/kbase/handle_service) TODO commit
* [KBase Handle Manager](https://github.com/kbase/handle_mngr) TODO commit
* [KBase Type Compiler](https://github.com/kbase/typecomp) (1)
* [PtKB operations](https://kbase.us/transfer-jgi-data/)
* [JGI Genome Portal](http://genome.jgi.doe.gov/)
* MySQL
* MongoDB

(1) The type compiler is only strictly necessary if the wipe server's API needs
to be changed and recompiled. However, the WSS and Handle services are also
accessed via type compiler generated clients.

Additionally, to really understand what's going on, some knowledge of the JGI
Java Server Faces front end, JGI Sequence Data Management (SDM) server, JGI JAMO
data management system, NERSC High Performance Storage System (HPSS), and
NERSC high.q job queue is useful.

PtKB sequence of events
-----------------------

* A user selects file(s) from a JGI genome portal organism page
  (e.g. [Blastococcus sp. URHD0036](http://genome.jgi.doe.gov/pages/dynamicOrganismDownload.jsf?organism=BlaspURHD0036))
  and completes the steps necessary to push the file(s) to KBase via the web UI.
* The JGI Java Server Faces front end passes the request to the SDM server.
* The SDM server runs a job on the NERSC high speed queue (high.q) to perform
  the PtKB operation.
* The job checks with JAMO to retrieve the file metadata and location for the
  file.
* If the file has previously been pushed to KBase, the Shock node ID for the
  file is retrieved and reused rather than pushing the file again. The job
  queries the Shock node to ensure it exists. If the file has never been pushed
  or the Shock node no longer exists:
  * If the data is not available in the disk cache, the job pulls the 
    data from HPSS and stores it in a disk cache.
  * The file is uploaded to Shock and a Shock node ID returned.
  * The Shock node ID is stored in an associative data structure with the file ID
    so the Shock node can be reused in later pushes.
* A handle for the Shock node is created (for a new Shock node) or looked up
  (for an existing node) in the Handle Service.
* If not already extant, a workspace is created in the WSS name for the
  organism and the PtKB user. This workspace is shared, read-only, with the
  user.
* A workspace object containing the handle and other data about the file,
  along with a provenance object, is uploaded to the Workspace service.

Endpoints
---------

genome.jgi.doe.gov is the production endpoint for PtKB (and all other Genome
Portal operations). This endpoint pushes to KBase production systems.

genomeportal.jgi.doe.gov is the dev/test endpoint for PtKB (and etc...). This
endpoint pushes to dev03.berkeley.kbase.us.

NOTE: the dev/test endpoint *cannot* retrieve data from HPSS. If a push
includes data that is only available on HPSS it will fail. The workaround is
to manually download the data from the JGI organism page, which will create
a disk cache that expires in 90 days.

Common (and not so common) errors
---------------------------------

* If tests are timing out with messages similar to 
  `Timed out attempting to access object 6133.1.38460.TGCTGG.adnq.fastq.gz with version 1 in workspace Bacillus_sp_JGI_001005J19_kbasetest after 1800 sec`
  and failure emails are sent with the contents `failed to restore all files in 9 hour period`
  the probable (but not definite) explanation is that the JGI disk cache for
  the file in question has expired and the file is only available on HPSS.
  Download the file from the production JGI endpoint to recreate the cache.
* Avast (and presumably other antivirus software) can interfere with 
  javamail and cause errors and hangs, even when disabled by the program UI.


