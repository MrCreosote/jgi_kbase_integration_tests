#BEGIN_HEADER
import subprocess
from subprocess import CalledProcessError
import pymongo

ALLOWED_USER = 'dev03wipe'
KB_SERVICES = '/kb/deployment/services/'
WS_START = KB_SERVICES + 'workspace/start_service'
WS_STOP = KB_SERVICES + 'workspace/stop_service'
SHOCK_START = KB_SERVICES + 'shock_service/start_service'
SHOCK_STOP = KB_SERVICES + 'shock_service/stop_service'
MONGO_HOST = 'localhost'
WS_DB = 'workspace'
WS_COL_SETTINGS = 'settings'
SHOCK_DB = 'ShockDB'
SHOCK_FILES_RM = 'rm -r /mnt/Shock/data/*'
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

        print "Stop ws"
        err_code, out = run_command(WS_STOP)
        output += out
        if err_code > 0:
            return err_code, output

        print "Stop shock"
        err_code, out = run_command(SHOCK_STOP)
        output += out
        if err_code > 0:
            return err_code, output

        print "Save ws settings"
        mc = pymongo.MongoClient(MONGO_HOST)
        settings = mc[WS_DB][WS_COL_SETTINGS].find_one()

        print "Drop mongo DBs"
        mc.drop_database(WS_DB)
        mc.drop_database(SHOCK_DB)

        # restore the workspace settings
        mc[WS_DB][WS_COL_SETTINGS].save(settings)

        # set the shock versions for now, hopefully this'll be fixed in a
        # new shock version
        vers = 'Versions'
        mc[SHOCK_DB][vers].save({'name': 'ACL', 'version': 2})
        mc[SHOCK_DB][vers].save({'name': 'Auth', 'version': 1})
        mc[SHOCK_DB][vers].save({'name': 'Node', 'version': 2})

        print "Delete shock files"
        err_code, out = run_command(SHOCK_FILES_RM)
        output += out
        if err_code > 0:
            return err_code, output

        print "Delete handle DB"
        # should really do this via a client, but it's not worth the effort
        err_code, out = run_command(MYSQL_CMD)
        output += out
        if err_code > 0:
            return err_code, output

        print "Start shock"
        err_code, out = run_command(SHOCK_START)
        output += out
        if err_code > 0:
            return err_code, output

        print "Start ws"
        err_code, out = run_command(WS_START)
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
