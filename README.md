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
* [KBase dev_container](https://github.com/kbase/dev_container)
* [KBase Workspace Service](https://github.com/kbase/workspace_deluxe) (WSS) 0.3.4
* [KBase Shock Service](https://github.com/kbase/shock_service) 0.9.6
* [KBase Handle Service](https://github.com/kbase/handle_service) 5eda76e
* [KBase Handle Manager](https://github.com/kbase/handle_mngr) 81297d5
* [KBase Type Compiler](https://github.com/kbase/typecomp) (1)
* [PtKB operations](https://kbase.us/transfer-jgi-data/)
* [JGI Genome Portal](http://genome.jgi.doe.gov/)
* [Jenkins](https://jenkins-ci.org/) and specifically the [KBase Jenkins instance](https://jenkins.kbase.us)
* [nginx](https://www.nginx.com/)
* [MySQL](https://www.mysql.com/)
* [MongoDB](https://www.mongodb.com)
* [Ubuntu](http://www.ubuntu.com/)
* git and github
* [Java](http://www.oracle.com/technetwork/java/javaee/overview/index.html) 1.7
* [ant](http://ant.apache.org/) 1.8.4
* [Python](https://www.python.org/) 2.7

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

[http://genome.jgi.doe.gov](http://genome.jgi.doe.gov) is the production
endpoint for PtKB (and all other Genome Portal operations). This endpoint
pushes to KBase production systems.

[http://genomeportal.jgi.doe.gov](http://genomeportal.jgi.doe.gov) is the
dev/test endpoint for PtKB (and etc...). This endpoint currently pushes to
dev03.berkeley.kbase.us.

**NOTE:** the dev/test endpoint *cannot* retrieve data from HPSS. If a push
includes data that is only available on HPSS it will fail. The workaround is
to manually download the data from the JGI organism page, which will create
a disk cache that expires in 90 days.

Install
-------

Note that without working with JGI to change where the dev/test endpoint
pushes, building a new test server won't do much good. Currently the test
server is dev03.berkeley.kbase.us - this is also a good reference to consult
if trying to duplicate or move the test server.

1. Obtain an Ubuntu machine with the KBase runtime installed (from the
   bootstrap repo). You probably already have access to such a machine if you
   work for KBase. If not, install the runtime as per directions (after
   installing Ubuntu if necessary).
2. Install the dev_container as per instructions.
3. Install shock_service, workspace service (workspace_deluxe repo),
   handle_service, handle_mngr as per instructions.
3. Clone this repo.
4. Install nginx.

Configure
---------

These instructions assume:

* The services will be accessed via nginx, and [nginx will be configured with a self signed certificate](https://www.digitalocean.com/community/tutorials/how-to-create-a-ssl-certificate-on-nginx-for-ubuntu-12-04) (SSC).
* MongoDB will run on the default port without auth.
* MySQL will run on the default port with no root password. It wouldn't be
  difficult to change this but hasn't so far been necessary.

1. Configure the Shock server as per instructions. Make sure to specify an
   administrative user (note in 0.9.6, the admin user in the config seems to
   be ignored. The wipe server contains a hack to work around this).
2. Configure the Handle Service as per instructions.
  1. Note that there are hardcoded system calls to curl in the Handle Service
     implementation file (produced by the type compiler). Since nginx uses a
     SSC, the implementation file must be hand edited to provide the
     `--insecure` command line option to all curl calls.
  2. As part of the configuration, you will need to set up appropriate MySQL
     tables for the use of the Handle Service.
3. Configure the Handle Manager as per instructions.
  1. Note that there are hardcoded system calls to curl in the Handle Manager
     implementation file (produced by the type compiler). Since nginx uses a
     SSC, the implementation file must be hand edited to provide the
     `--insecure` command line option to all curl calls.
  2. Ensure the shock administrator credentials provided in the deploy.cfg file
     match the administrator in the Shock configuration file.
4. Configure the Workspace service as per instructions.
  1. Ensure the handle manager credentials provided in the deploy.cfg file
     match the allowed user in the handle manager configuration file.
5. Configure the wipe server.
  1. The configuration for the Wipe server is contained in the server
     implementation file in this repo at
     lib/biokbase/wipe_dev03/impl.py.
6. Configure the tests
  1. User accounts are configured in ./test.cfg to avoid checking credentials
     into git. In any case, use throwaway accounts to run the tests as they
     may be exposed publicly.
  2. The remaining configuration is in the main test file at
     src/us/kbase/jgiintegration/test/JGIIntegrationTest.java.
     
//TODO config nginx

Note that the shock administration user has to be set consistently in many
places - currently in the Shock configuration file, the Handle Manger
configuration file, the Wipe Server impl.py file, and the test.cfg file.

Start services
--------------

This list assumes MySQL and nginx start on boot.

1. Start MongoDB without auth. A typical command line:
   `/kb/runtime/bin/mongod --dbpath /mnt2/mongodata/mongodata >> /mnt2/mongodata/mongolog 2>&1 &`
2. Start shock with the start script listed in the wipe server config:
   ` /kb/deployment/services/shock_service/start_service`
3. Start the Handle Service:
  1. Since nginx is configured with an SSC, tell the Perl LWP module to allow them:
     `export PERL_LWP_SSL_VERIFY_HOSTNAME=0`
  2. Start the service:
     `/kb/deployment/services/handle_service/start_service`
4. Start the Handle Manager:
  1. Since nginx is configured with an SSC, tell the Perl LWP module to allow them:
     `export PERL_LWP_SSL_VERIFY_HOSTNAME=0`
  2. Start the service:
     `/kb/deployment/services/handle_mngr/start_service &`
5. Start the workspace service:
   `/kb/deployment/services/workspace/start_service`
6. Start the wipe service:

        export PYTHONPATH=/kb/dev_container/modules/jgi_kbase_integration_tests/lib/
        cd /kb/dev_container/modules/jgi_kbase_integration_tests/lib/
        python biokbase/wipe_dev03/server.py --host 0.0.0.0 --port 9000 >> wipelog 2>&1 &

Run tests
---------

To run tests, simply run `make` and the Makefile will run the tests. Java,
Python, and ant will need to be correctly configured.

### Jenkins

Below is a typical config from the "Execute shell" portion of the KBase
Jenkins config. The rest of the configuration is pretty standard.


    export KB_RUNTIME=/kbase/runtimes/20140109-prod
    export JAVA_HOME=$KB_RUNTIME/java
    export PATH=$KB_RUNTIME/bin:$JAVA_HOME/bin:$PATH
    export ANT_HOME=$KB_RUNTIME/ant
    
    make test TESTCFG=/home/jenkins/gavin/jgi_kbase_integration_tests/test.cfg
    
Make sure all the Jenkins workers have the test.cfg properly filled out.


Common (and not so common) errors
---------------------------------

The first thing to try, if the tests fail and the cause is not obvious, is
manually pushing some files and observing the results. However, some common
errors and their potential causes are listed below.

* If tests are timing out with messages similar to 
  `Timed out attempting to access object 6133.1.38460.TGCTGG.adnq.fastq.gz with version 1 in workspace Bacillus_sp_JGI_001005J19_kbasetest after 1800 sec`
  and failure emails are sent with the contents `failed to restore all files in 9 hour period`
  the probable (but not definite) explanation is that the JGI disk cache for
  the file in question has expired and the file is only available on HPSS.
  Download the file from the production JGI endpoint to recreate the cache.
* If tests are failing because the workspace is throwing 404s, 502s,
  connection refused, or consistently timing out it's probably due to [WOR-204](https://atlassian.kbase.us/browse/WOR-204).
  A PermGen error being thrown or occuring in the workspace logs is strong
  confirmation. If this occurs `kill -9` the workspace and restart.  
* Avast (and presumably other antivirus software) can interfere with 
  javamail and cause errors and hangs, even when disabled by the program UI.


