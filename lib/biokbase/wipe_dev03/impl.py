#BEGIN_HEADER
import subprocess
from subprocess import CalledProcessError
import pymongo

'''
This service prepares an JGI / KBase Push to KBase (PtKB) installation for
testing by wiping the Shock, Workspace, and Handle Service databases.
Configuration options are set below.

To understand exactly what the server does, read the wipe_dev03 method.
'''

# The KBase user that is allowed to call methods on this server.
ALLOWED_USER = 'dev03wipe'
# The directory where KBase service files are installed.
KB_SERVICES = '/kb/deployment/services/'
# The location of the start script for the workspace service.
WS_START = KB_SERVICES + 'workspace/start_service'
# The location of the stop script for the workspace service.
WS_STOP = KB_SERVICES + 'workspace/stop_service'
# The location of the start script for the shock service.
SHOCK_START = KB_SERVICES + 'shock_service/start_service'
# The location of the stop script for the shock service.
SHOCK_STOP = KB_SERVICES + 'shock_service/stop_service'
# The address of the MongoDB server.
MONGO_HOST = 'localhost'
# The workspace mongo database name.
WS_DB = 'workspace'
# The index collection in the workspace mongo database.
WS_COL_INDEX = 'system.indexes'
# The collection in the workspace mongo database that contains the database
# settings.
WS_COL_SETTINGS = 'settings'
# The shock server mongo database name.
SHOCK_DB = 'ShockDB'
# The KBase username of the shock administrator.
SHOCK_ADMIN = 'lolcatservice'
# The command used to delete the shock service filesystem.
SHOCK_FILES_RM = 'rm -r /mnt/Shock/data/*'
# The command used to empty (but not remove) the Handle Service MySQL database.
MYSQL_CMD = 'mysql -u root -e "truncate table hsi.Handle;"'


def run_command(command):
    err = 0
    try:
        output = subprocess.check_output(command, stderr=subprocess.STDOUT,
                                         shell=True, close_fds=True)
    except CalledProcessError as cpe:
        err = cpe.returncode
        output = cpe.output
    return err, output

#END_HEADER


class WipeDev03:
    '''
    Module Name:
    WipeDev03

    Module Description:
    Destroy the dev03 workspace, handle, and shock databases.
    '''

    ######## WARNING FOR GEVENT USERS #######
    # Since asynchronous IO can lead to methods - even the same method -
    # interrupting each other, you must be *very* careful when using global
    # state. A method could easily clobber the state set by another while
    # the latter method is running.
    #########################################
    #BEGIN_CLASS_HEADER
    def checkUser(self):
        if self.ctx['user_id'] != ALLOWED_USER:
            raise Exception("User unauthorized")
    #END_CLASS_HEADER

    # config contains contents of config file in a hash or None if it couldn't
    # be found
    def __init__(self, config):
        #BEGIN_CONSTRUCTOR
        #END_CONSTRUCTOR
        pass

    def wipe_dev03(self):
        # self.ctx is set by the wsgi application class
        # return variables are: err_code, output
        #BEGIN wipe_dev03
        self.checkUser()
        output = ''

        # Stop the Shock service
        print "Stop shock"
        err_code, out = run_command(SHOCK_STOP)
        output += out
        if err_code > 0:
            return err_code, output

        # Remove the workspace and shock databases (but not the workspace type
        # database)
        print "Drop mongo DBs"
        mc = pymongo.MongoClient(MONGO_HOST)
        for col in mc[WS_DB].collection_names():
            if col != WS_COL_INDEX and col != WS_COL_SETTINGS:
                mc[WS_DB][col].remove({})  # drop removes indexes
        mc.drop_database(SHOCK_DB)

        # set the shock collection versions for now, hopefully this'll be fixed
        # in a new shock version
        vers = 'Versions'
        mc[SHOCK_DB][vers].save({'name': 'ACL', 'version': 2})
        mc[SHOCK_DB][vers].save({'name': 'Auth', 'version': 1})
        mc[SHOCK_DB][vers].save({'name': 'Node', 'version': 2})

        # set a shock admin, this apparently is broken in 0.9.6 (?)
        mc[SHOCK_DB]['Users'].save(
            {"uuid": "095abbb0-07cc-43b3-8fd9-98edfb2541be",
             "username": SHOCK_ADMIN,
             "fullname": "",
             "email": "",
             "password": "",
             "shock_admin": True}
        )

        # Delete the shock filesystem.
        print "Delete shock files"
        err_code, out = run_command(SHOCK_FILES_RM)
        output += out
        if err_code > 0:
            return err_code, output

        # Delete the handle service table.
        print "Delete handle DB"
        # should really do this via a client, but it's not worth the effort
        err_code, out = run_command(MYSQL_CMD)
        output += out
        if err_code > 0:
            return err_code, output

        # restart the shock service
        print "Start shock"
        err_code, out = run_command(SHOCK_START)
        output += out
        if err_code > 0:
            return err_code, output

        #END wipe_dev03

        #At some point might do deeper type checking...
        if not isinstance(err_code, int):
            raise ValueError('Method wipe_dev03 return value ' +
                             'err_code is not type int as required.')
        if not isinstance(output, basestring):
            raise ValueError('Method wipe_dev03 return value ' +
                             'output is not type basestring as required.')
        # return the results
        return [err_code, output]

    def shut_down_workspace(self):
        # self.ctx is set by the wsgi application class
        # return variables are: err_code, output
        #BEGIN shut_down_workspace
        self.checkUser()
        print "Stop ws"
        err_code, output = run_command(WS_STOP)
        #END shut_down_workspace

        #At some point might do deeper type checking...
        if not isinstance(err_code, int):
            raise ValueError('Method shut_down_workspace return value ' +
                             'err_code is not type int as required.')
        if not isinstance(output, basestring):
            raise ValueError('Method shut_down_workspace return value ' +
                             'output is not type basestring as required.')
        # return the results
        return [err_code, output]

    def restart_workspace(self):
        # self.ctx is set by the wsgi application class
        # return variables are: err_code, output
        #BEGIN restart_workspace
        self.checkUser()
        print "Start ws"
        err_code, output = run_command(WS_START)
        #END restart_workspace

        #At some point might do deeper type checking...
        if not isinstance(err_code, int):
            raise ValueError('Method restart_workspace return value ' +
                             'err_code is not type int as required.')
        if not isinstance(output, basestring):
            raise ValueError('Method restart_workspace return value ' +
                             'output is not type basestring as required.')
        # return the results
        return [err_code, output]
