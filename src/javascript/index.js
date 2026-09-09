import {registry} from '@jahia/ui-extender';

export default function () {
    registry.add('callback', 'geo-readiness', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            const {default: register} = await import('./init');
            register();
        }
    });
    console.info('%c GEO Readiness is activated', 'color: #0086BF');
}
