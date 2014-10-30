#BEGIN_HEADER
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
    #END_CLASS_HEADER

    # config contains contents of config file in a hash or None if it couldn't
    # be found
    def __init__(self, config):
        #BEGIN_CONSTRUCTOR
        #END_CONSTRUCTOR
        pass

    def wipe_dev03(self):
        # self.ctx is set by the wsgi application class
        # return variables are: output
        #BEGIN wipe_dev03
        #END wipe_dev03

        #At some point might do deeper type checking...
        if not isinstance(output, basestring):
            raise ValueError('Method wipe_dev03 return value ' +
                             'output is not type basestring as required.')
        # return the results
        return [output]
